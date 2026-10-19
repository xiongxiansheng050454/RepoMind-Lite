package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import com.repomind.lite.application.IndexClaim;
import com.repomind.lite.domain.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.http.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class GitRepositoryService {
    public record Checkout(Path path, String commitSha, String defaultBranch) {
    }

    private final RepositoryUrlValidator validator;

    public GitRepositoryService(RepositoryUrlValidator validator) {
        this.validator = validator;
    }

    public Checkout checkout(IndexClaim claim) throws Exception {
        var remote = validator.validate(claim.repository().repoUrl(), claim.repository().ref());
        Path parent = claim.repository().workDir();
        // Each lease has an isolated directory so a recovered worker cannot mutate a newer checkout.
        Path path = parent.resolve(claim.jobId() + "-" + claim.token()).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Instant deadline = Instant.now().plusSeconds(180);
        ProgressMonitor monitor = new ProgressMonitor() {
            @Override
            public void start(int tasks) {
            }

            @Override
            public void beginTask(String name, int total) {
            }

            @Override
            public void update(int completed) {
            }

            @Override
            public void endTask() {
            }

            @Override
            public void showDuration(boolean enabled) {
            }

            @Override
            public boolean isCancelled() {
                return Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted();
            }
        };
        try (Git git = Git.cloneRepository().setURI(remote.url()).setDirectory(path.toFile()).setNoCheckout(true).setCloneAllBranches(true).setTimeout(30).setProgressMonitor(monitor).setTransportConfigCallback(transport -> {
            if (!(transport instanceof TransportHttp http))
                throw new IllegalArgumentException("Only HTTPS Git transport is supported");
            http.setHttpConnectionFactory(new HttpConnectionFactory() {
                @Override
                public HttpConnection create(URL url) throws IOException {
                    return new PinnedHttpsConnection(url);
                }

                @Override
                public HttpConnection create(URL url, Proxy proxy) throws IOException {
                    return new PinnedHttpsConnection(url);
                }
            });
            transport.setCredentialsProvider(new CredentialsProvider() {
                @Override
                public boolean isInteractive() {
                    return false;
                }

                @Override
                public boolean supports(CredentialItem... items) {
                    return false;
                }

                @Override
                public boolean get(URIish uri, CredentialItem... items) {
                    return false;
                }
            });
        }).call()) {
            var repository = git.getRepository();
            String defaultBranch = repository.getBranch();
            ObjectId commit = resolve(repository, remote.ref());
            if (commit == null) throw new IOException("Requested Git ref could not be resolved");
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
                var revision = walk.parseCommit(commit);
                try (var tree = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                    tree.addTree(revision.getTree());
                    tree.setRecursive(true);
                    long bytes = 0;
                    int files = 0;
                    while (tree.next()) {
                        if (++files > 50000) throw new IOException("Repository exceeds file limit");
                        if (tree.getFileMode(0) == FileMode.SYMLINK || tree.getFileMode(0) == FileMode.GITLINK)
                            continue;
                        bytes += repository.open(tree.getObjectId(0)).getSize();
                        if (bytes > 256L * 1024 * 1024) throw new IOException("Repository exceeds checkout size limit");
                    }
                }
            }
            git.checkout().setName(commit.name()).setForced(true).call();
            return new Checkout(path, commit.name(), defaultBranch);
        }
    }

    private static ObjectId resolve(Repository repository, String ref) throws IOException {
        if (ref.equals("HEAD")) return repository.resolve("HEAD^{commit}");
        for (String candidate : List.of("refs/remotes/origin/" + ref, "refs/tags/" + ref, ref)) {
            var commit = repository.resolve(candidate + "^{commit}");
            if (commit != null) return commit;
        }
        return null;
    }

    public FileExcerpt readFile(RepositoryRecord repository, String path, int startLine, int endLine) {
        if (startLine < 1 || endLine < startLine || endLine - startLine >= 200)
            throw BusinessException.badRequest("File range must contain 1 to 200 lines");
        Path file = checkedPath(repository, path);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024)
                throw BusinessException.badRequest("File is unavailable or too large");
            List<String> lines = Files.readAllLines(file);
            if (startLine > lines.size()) throw BusinessException.badRequest("File range is outside the file");
            int end = Math.min(endLine, lines.size());
            String content = String.join("\n", lines.subList(startLine - 1, end));
            if (content.length() > 16000) throw BusinessException.badRequest("File excerpt exceeds size limit");
            return new FileExcerpt(path, startLine, end, content);
        } catch (IOException ex) {
            throw BusinessException.badRequest("File could not be read");
        }
    }

    public List<GitCommit> gitLog(RepositoryRecord repository, String path, int limit) {
        if (limit < 1 || limit > 20) throw BusinessException.badRequest("History limit must be between 1 and 20");
        if (path != null && !path.isBlank()) checkedPath(repository, path);
        try (Git git = Git.open(repository.workDir().toFile())) {
            var command = git.log().add(git.getRepository().resolve(repository.commitSha())).setMaxCount(limit);
            if (path != null && !path.isBlank()) command.addPath(path);
            var commits = new ArrayList<GitCommit>();
            for (var commit : command.call())
                commits.add(new GitCommit(commit.name().substring(0, 12), commit.getAuthorIdent().getName(), commit.getFullMessage().substring(0, Math.min(commit.getFullMessage().length(), 4000)), Instant.ofEpochSecond(commit.getCommitTime())));
            return List.copyOf(commits);
        } catch (Exception ex) {
            throw BusinessException.badRequest("Git history could not be read");
        }
    }

    private static Path checkedPath(RepositoryRecord repository, String path) {
        if (path == null || path.isBlank() || path.length() > 1024 || path.contains("\\") || path.startsWith("/") || path.contains(":") || path.chars().anyMatch(Character::isISOControl))
            throw BusinessException.badRequest("File path is invalid");
        Path relative = Path.of(path);
        for (Path part : relative)
            if (part.toString().equals("..") || part.toString().equals(".git"))
                throw BusinessException.badRequest("File path is outside the repository");
        Path root = repository.workDir().toAbsolutePath().normalize(), file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw BusinessException.badRequest("File path is outside the repository");
        for (Path current = file; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw BusinessException.badRequest("Symlink paths are not readable");
        return file;
    }
}

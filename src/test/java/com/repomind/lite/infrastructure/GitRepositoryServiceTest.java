package com.repomind.lite.infrastructure;

import com.repomind.lite.domain.RepositoryRecord;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class GitRepositoryServiceTest {
    @TempDir
    Path root;
    GitRepositoryService service = new GitRepositoryService(new RepositoryUrlValidator());

    private RepositoryRecord record(String sha) {
        return new RepositoryRecord(UUID.randomUUID(), "https://github.com/acme/repo.git", "HEAD", "READY", root, sha, "main");
    }

    @Test
    void preventsTraversalAndGitInternals() throws Exception {
        Files.writeString(root.resolve("Demo.java"), "line one\nline two\nline three\n");
        assertThat(service.readFile(record(""), "Demo.java", 2, 3).content()).isEqualTo("line two\nline three");
        for (String path : new String[]{"../secrets", ".git/config", "/etc/passwd", "C:/secret", "nested/../../secret"})
            assertThatThrownBy(() -> service.readFile(record(""), path, 1, 2)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void readsFullHistoryPinnedToRepositoryCommit() throws Exception {
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(root.resolve("Demo.java"), "class Demo {}\n");
            git.add().addFilepattern("Demo.java").call();
            var initial = git.commit().setMessage("Initial").setAuthor("Author", "a@example.org").call();
            Files.writeString(root.resolve("Demo.java"), "class Demo { int x; }\n");
            git.add().addFilepattern("Demo.java").call();
            git.commit().setMessage("Second").setAuthor("Author", "a@example.org").call();
            assertThat(service.gitLog(record(initial.name()), "Demo.java", 20)).hasSize(1).first().extracting(c -> c.message()).isEqualTo("Initial");
        }
    }
}

package com.repomind.lite.application;

import com.repomind.lite.errors.BusinessException;
import com.repomind.lite.domain.RepositoryRecord;
import com.repomind.lite.infrastructure.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Service
public class RepositoryService {
    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);

    private final IndexStore store;
    private final RepositoryUrlValidator validator;
    private final LlmSettings llm;
    private final Path workRoot;

    public RepositoryService(IndexStore store, RepositoryUrlValidator validator, LlmSettings llm, @Value("${repomind.repositories.work-dir}") String workRoot) {
        this.store = store;
        this.validator = validator;
        this.llm = llm;
        this.workRoot = Path.of(workRoot);
    }

    public RepositorySubmission submit(String repoUrl, String ref) {
        if (!llm.current().configured())
            throw BusinessException.unavailable("LLM_NOT_CONFIGURED", "Configure the model base URL and API key before indexing a repository");
        return store.submitApplication(validator.validate(repoUrl, ref), workRoot);
    }

    public void delete(UUID id) {
        store.delete(id);
        removeWorkFiles(id);
    }

    private void removeWorkFiles(UUID id) {
        Path root = workRoot.toAbsolutePath().normalize();
        Path dir = root.resolve(id.toString()).normalize();
        if (!dir.startsWith(root) || dir.equals(root) || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    file.toFile().setWritable(true);
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.warn("Repository {} was deleted but its files could not be fully removed: {}", id, ex.toString());
        }
    }

    public RepositoryRecord get(UUID id) {
        return store.get(id);
    }

    public IndexStatus indexStatus(UUID id) {
        return store.statusApplication(id);
    }

    public RepositoryRecord requireReady(UUID id) {
        RepositoryRecord repository = get(id);
        if (!repository.status().equals("READY"))
            throw BusinessException.conflict("REPOSITORY_NOT_READY", "Repository indexing must finish before this operation");
        return repository;
    }
}

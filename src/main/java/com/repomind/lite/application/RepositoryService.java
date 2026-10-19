package com.repomind.lite.application;

import com.repomind.lite.errors.BusinessException;
import com.repomind.lite.domain.RepositoryRecord;
import com.repomind.lite.infrastructure.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.UUID;

@Service
public class RepositoryService {
    private final IndexStore store;
    private final RepositoryUrlValidator validator;
    private final Path workRoot;

    public RepositoryService(IndexStore store, RepositoryUrlValidator validator, @Value("${repomind.repositories.work-dir}") String workRoot) {
        this.store = store;
        this.validator = validator;
        this.workRoot = Path.of(workRoot);
    }

    public RepositorySubmission submit(String repoUrl, String ref) {
        return store.submitApplication(validator.validate(repoUrl, ref), workRoot);
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

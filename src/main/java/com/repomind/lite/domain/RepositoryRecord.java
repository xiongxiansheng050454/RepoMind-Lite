package com.repomind.lite.domain;

import java.nio.file.Path;
import java.util.UUID;

public record RepositoryRecord(UUID id, String repoUrl, String ref, String status, Path workDir, String commitSha,
                               String defaultBranch) {
}

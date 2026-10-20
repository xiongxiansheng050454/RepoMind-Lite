package com.repomind.lite.api;

import com.repomind.lite.application.IndexStatus;
import com.repomind.lite.application.RepositorySubmission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RepositoryResponses {
    private RepositoryResponses() {}

    public record Submission(UUID repositoryId, UUID jobId, String status) {
        public static Submission from(RepositorySubmission value) {
            return new Submission(value.repositoryId(), value.jobId(), value.status());
        }
    }

    public record IndexStatusResponse(UUID repositoryId, UUID jobId, String status, String stage, double progress,
                                      int retryCount, int filesProcessed, int chunksCreated, List<String> warnings,
                                      int warningCount, String error, String errorCode, String failedStage,
                                      Instant startedAt, Instant completedAt) {
        public static IndexStatusResponse from(IndexStatus value) {
            return new IndexStatusResponse(value.repositoryId(), value.jobId(), value.status(), value.stage(),
                    value.progress(), value.retryCount(), value.filesProcessed(), value.chunksCreated(),
                    value.warnings(), value.warningCount(), value.error(), value.errorCode(), value.failedStage(),
                    value.startedAt(), value.completedAt());
        }
    }
}

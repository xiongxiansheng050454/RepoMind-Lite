package com.repomind.lite.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IndexStatus(UUID repositoryId, UUID jobId, String status, String stage, double progress,
                          int retryCount, int filesProcessed, int chunksCreated, List<String> warnings,
                          String error, String errorCode, String failedStage, Instant startedAt, Instant completedAt) {
    public int warningCount() { return warnings.size(); }
}

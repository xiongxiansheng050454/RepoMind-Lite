package com.repomind.lite.application;

import java.util.UUID;

public record RepositorySubmission(UUID repositoryId, UUID jobId, String status) {}

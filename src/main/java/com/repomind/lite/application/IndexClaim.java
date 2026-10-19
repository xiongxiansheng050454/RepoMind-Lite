package com.repomind.lite.application;

import com.repomind.lite.domain.RepositoryRecord;
import java.util.UUID;

/** Execution lease handed from the queue adapter to the indexing use case. */
public record IndexClaim(UUID jobId, UUID token, RepositoryRecord repository, int attempt) {}

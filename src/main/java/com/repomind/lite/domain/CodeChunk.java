package com.repomind.lite.domain;

import java.util.UUID;

public record CodeChunk(UUID id, UUID repositoryId, String path, String symbol, int startLine, int endLine,
                        String content) {
}

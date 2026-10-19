package com.repomind.lite.domain;

import java.time.Instant;

public record GitCommit(String sha, String author, String message, Instant time) {
}

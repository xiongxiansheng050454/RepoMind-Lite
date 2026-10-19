package com.repomind.lite.domain;

public record FileExcerpt(String path, int startLine, int endLine, String content) {
}

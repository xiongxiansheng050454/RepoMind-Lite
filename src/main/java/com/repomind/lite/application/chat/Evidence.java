package com.repomind.lite.application.chat;

import com.repomind.lite.errors.ChatFailure;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates that final answer citations stay within observed repository evidence. */
public final class Evidence {
    private static final Pattern CITATION = Pattern.compile("\\[([^:\\]]+):L(\\d+)-L(\\d+)\\]");
    private final Map<SourceKey, Observed> sources = new LinkedHashMap<>();
    private long sequence;

    public void add(String path, int startLine, int endLine, String content, String explanation, SourceKind kind) {
        if (path == null || path.isBlank() || startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Invalid evidence range");
        }
        Source source = new Source(path, startLine, endLine, content, explanation, Objects.requireNonNull(kind));
        SourceKey key = new SourceKey(path, startLine, endLine, content);
        Observed previous = sources.get(key);
        if (previous == null || source.kind().priority() > previous.source().kind().priority()) {
            sources.put(key, new Observed(source, previous == null ? sequence++ : previous.sequence()));
        }
    }

    public List<Source> validate(String answer) {
        if (answer == null || answer.isBlank()) throw new ChatFailure("Answer is empty");
        Map<CitationKey, Source> cited = new LinkedHashMap<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            String path = matcher.group(1);
            int start = Integer.parseInt(matcher.group(2));
            int end = Integer.parseInt(matcher.group(3));
            if (start > end) throw new ChatFailure("Citation line range is invalid");
            Source source = sources.values().stream()
                    .map(Observed::source)
                    .filter(candidate -> candidate.path().equals(path)
                            && start >= candidate.startLine() && end <= candidate.endLine())
                    .min(bestMatch(start, end))
                    .orElseThrow(() -> new ChatFailure("Citation is outside observed evidence"));
            cited.putIfAbsent(new CitationKey(path, start, end), source);
        }
        if (sources.isEmpty()) {
            if (!answer.toLowerCase().contains("cannot confirm") && !answer.toLowerCase().contains("unable to confirm")) {
                throw new ChatFailure("Answer has no repository evidence");
            }
        } else if (cited.isEmpty()) {
            throw new ChatFailure("Answer must cite repository evidence");
        }
        return List.copyOf(cited.values());
    }

    private Comparator<Source> bestMatch(int start, int end) {
        return Comparator
                .comparing((Source source) -> source.startLine() == start && source.endLine() == end ? 0 : 1)
                .thenComparingInt(source -> source.endLine() - source.startLine())
                .thenComparing(Comparator.comparingInt((Source source) -> source.kind().priority()).reversed())
                .thenComparingLong(source -> sources.get(new SourceKey(source.path(), source.startLine(), source.endLine(), source.content())).sequence());
    }

    public enum SourceKind {
        INITIAL_SEARCH(1),
        SEARCH_CODE(2),
        FILE_EXCERPT(3);

        private final int priority;

        SourceKind(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    public record Source(String path, int startLine, int endLine, String content, String explanation, SourceKind kind) {}

    private record SourceKey(String path, int startLine, int endLine, String content) {}

    private record CitationKey(String path, int startLine, int endLine) {}

    private record Observed(Source source, long sequence) {}
}

package com.repomind.lite.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.lite.domain.FileExcerpt;
import com.repomind.lite.domain.GitCommit;
import com.repomind.lite.domain.SearchHit;
import com.repomind.lite.application.SearchService;
import com.repomind.lite.infrastructure.GitRepositoryService;
import com.repomind.lite.domain.RepositoryRecord;
import com.repomind.lite.errors.ChatFailure;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Repository-scoped tools exposed to the model for one chat request. */
public final class RepositoryTools {
    private final UUID repositoryId;
    private final RepositoryRecord repository;
    private final SearchService search;
    private final GitRepositoryService git;
    private final ObjectMapper json;
    private final Evidence evidence;
    private final AtomicInteger calls;

    public RepositoryTools(UUID repositoryId, RepositoryRecord repository, SearchService search,
                           GitRepositoryService git, ObjectMapper json, Evidence evidence,
                           AtomicInteger calls) {
        this.repositoryId = repositoryId;
        this.repository = repository;
        this.search = search;
        this.git = git;
        this.json = json;
        this.evidence = evidence;
        this.calls = calls;
    }

    @Tool("Search indexed Java code in the current repository")
    public String search_code(String query, int topK) {
        enter();
        if (topK < 1 || topK > 20) throw new ChatFailure("topK must be between 1 and 20");
        List<SearchHit> hits = search.search(repositoryId, query, topK);
        hits.forEach(hit -> evidence.add(hit.chunk().path(), hit.chunk().startLine(), hit.chunk().endLine(),
                hit.chunk().content(), "Code search result", Evidence.SourceKind.SEARCH_CODE));
        return write(hits.stream().map(hit -> new SearchResult(hit.chunk().path(), hit.chunk().symbol(),
                hit.chunk().startLine(), hit.chunk().endLine(), hit.chunk().content(), hit.score())).toList());
    }

    @Tool("Read exact lines from a file in the current repository")
    public String read_file(String path, int startLine, int endLine) {
        enter();
        FileExcerpt excerpt = git.readFile(repository, path, startLine, endLine);
        evidence.add(excerpt.path(), excerpt.startLine(), excerpt.endLine(), excerpt.content(), "File excerpt",
                Evidence.SourceKind.FILE_EXCERPT);
        return write(excerpt);
    }

    @Tool("Read recent Git history for a file in the current repository")
    public String git_log(String path, int limit) {
        enter();
        if (limit < 1 || limit > 20) throw new ChatFailure("limit must be between 1 and 20");
        List<GitCommit> commits = git.gitLog(repository, path, limit);
        return write(commits);
    }

    private void enter() {
        calls.incrementAndGet();
    }

    private String write(Object value) {
        try {
            String result = json.writeValueAsString(value);
            if (result.length() > 24000) return result.substring(0, 24000);
            return result;
        } catch (Exception ex) {
            throw new ChatFailure("Tool result could not be encoded");
        }
    }

    public record SearchResult(String path, String symbol, int startLine, int endLine, String content, double score) {}
}

package com.repomind.lite.application;

import com.repomind.lite.errors.BusinessException;
import com.repomind.lite.domain.*;
import com.repomind.lite.infrastructure.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import com.repomind.lite.infrastructure.CodeSearchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SearchService {
    private final RepositoryService repositories;
    private final EmbeddingGateway embeddings;
    private final CodeSearchRepository searchRepository;
    private final MeterRegistry meters;

    @Autowired
    public SearchService(RepositoryService repositories, EmbeddingGateway embeddings, CodeSearchRepository searchRepository, MeterRegistry meters) {
        this.repositories = repositories;
        this.embeddings = embeddings;
        this.searchRepository = searchRepository;
        this.meters = meters;
    }

    public List<SearchHit> search(UUID repositoryId, String query, int topK) {
        if (topK < 1 || topK > 20) throw BusinessException.badRequest("topK must be between 1 and 20");
        if (query == null || query.isBlank() || query.length() > 2000)
            throw BusinessException.badRequest("Search query must contain 1 to 2000 characters");
        repositories.requireReady(repositoryId);
        var sample = Timer.start(meters);
        try {
            return searchVector(repositoryId, query, topK, embeddings.embed(query));
        } finally {
            sample.stop(meters.timer("repomind.search.duration"));
        }
    }

    /**
     * Low-level retrieval remains repository scoped; caller supplies a validated embedding.
     */
    public List<SearchHit> searchVector(UUID repositoryId, String query, int topK, float[] embedding) {
        if (topK < 1 || topK > 20) throw BusinessException.badRequest("topK must be between 1 and 20");
        return searchRepository.search(repositoryId, query, topK, embedding);
    }
}

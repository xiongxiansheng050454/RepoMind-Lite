package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class EmbeddingGateway {
    static final int DIMENSIONS = 1536;
    private final LlmSettings settings;
    private final MeterRegistry meters;
    private volatile LlmSettings.Snapshot builtFor;
    private volatile OpenAiEmbeddingModel model;

    @Autowired
    public EmbeddingGateway(LlmSettings settings, MeterRegistry meters) {
        this.settings = settings;
        this.meters = meters;
    }

    public EmbeddingGateway(String baseUrl, String apiKey, String modelName, MeterRegistry meters) {
        this(LlmSettings.fixed(baseUrl, apiKey, "unused", modelName, 1024), meters);
    }

    public boolean configured() {
        return settings.current().configured();
    }

    public float[] embed(String text) {
        return embed(List.of(text)).get(0);
    }

    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (texts.size() > 32) throw new IllegalArgumentException("Embedding batch limit is 32");
        var sample = Timer.start(meters);
        try {
            var response = model().embedAll(texts.stream().map(TextSegment::from).toList()).content();
            if (response.size() != texts.size()) throw new IllegalStateException("Embedding response count mismatch");
            var vectors = response.stream().map(Embedding::vector).toList();
            for (float[] vector : vectors) {
                if (vector.length != DIMENSIONS)
                    throw new BusinessException(com.repomind.lite.errors.FailureType.BAD_REQUEST, "EMBEDDING_DIMENSION",
                            "The embedding model returned " + vector.length + "-dimensional vectors but the index requires " + DIMENSIONS);
                IndexStore.vector(vector);
            }
            return vectors;
        } finally {
            sample.stop(meters.timer("repomind.embedding.duration"));
        }
    }

    private OpenAiEmbeddingModel model() {
        LlmSettings.Snapshot snapshot = settings.current();
        if (!snapshot.configured())
            throw BusinessException.unavailable("LLM_NOT_CONFIGURED", "The language model service is not configured");
        if (model == null || !snapshot.equals(builtFor)) synchronized (this) {
            if (model == null || !snapshot.equals(builtFor)) {
                model = OpenAiEmbeddingModel.builder().baseUrl(snapshot.baseUrl()).apiKey(snapshot.apiKey())
                        .modelName(snapshot.embeddingModel()).dimensions(DIMENSIONS).timeout(Duration.ofSeconds(30))
                        .maxRetries(0).logRequests(false).logResponses(false).build();
                builtFor = snapshot;
            }
        }
        return model;
    }
}

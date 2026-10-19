package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class EmbeddingGateway {
    private final String baseUrl, apiKey, modelName;
    private final MeterRegistry meters;
    private volatile OpenAiEmbeddingModel model;

    public EmbeddingGateway(@Value("${repomind.llm.base-url}") String baseUrl, @Value("${repomind.llm.api-key}") String apiKey, @Value("${repomind.llm.embedding-model}") String modelName, MeterRegistry meters) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.meters = meters;
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
            for (float[] vector : vectors) IndexStore.vector(vector);
            return vectors;
        } finally {
            sample.stop(meters.timer("repomind.embedding.duration"));
        }
    }

    private OpenAiEmbeddingModel model() {
        if (apiKey == null || apiKey.isBlank())
            throw BusinessException.unavailable("LLM_NOT_CONFIGURED", "The language model service is not configured");
        if (model == null) synchronized (this) {
            if (model == null)
                model = OpenAiEmbeddingModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName).dimensions(1536).timeout(Duration.ofSeconds(30)).maxRetries(0).logRequests(false).logResponses(false).build();
        }
        return model;
    }
}

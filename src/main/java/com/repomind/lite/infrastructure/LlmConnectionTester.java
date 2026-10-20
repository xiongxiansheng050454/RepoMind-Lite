package com.repomind.lite.infrastructure;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
public class LlmConnectionTester {
    public record Part(boolean ok, String code, String message) {
    }

    public record Result(Part chat, Part embedding) {
    }

    public Result test(LlmSettings.Snapshot snapshot) {
        if (!snapshot.configured()) {
            Part missing = new Part(false, "LLM_NOT_CONFIGURED", "The API key is not set");
            return new Result(missing, missing);
        }
        var chat = CompletableFuture.supplyAsync(() -> chat(snapshot));
        var embedding = CompletableFuture.supplyAsync(() -> embedding(snapshot));
        return new Result(chat.join(), embedding.join());
    }

    private Part chat(LlmSettings.Snapshot snapshot) {
        try {
            var model = OpenAiChatModel.builder().baseUrl(snapshot.baseUrl()).apiKey(snapshot.apiKey())
                    .modelName(snapshot.model()).maxTokens(8).timeout(Duration.ofSeconds(15)).maxRetries(0)
                    .logRequests(false).logResponses(false).build();
            model.chat("ping");
            return new Part(true, "OK", "Chat model responded");
        } catch (Exception ex) {
            return failed(ex);
        }
    }

    private Part embedding(LlmSettings.Snapshot snapshot) {
        try {
            var model = OpenAiEmbeddingModel.builder().baseUrl(snapshot.baseUrl()).apiKey(snapshot.apiKey())
                    .modelName(snapshot.embeddingModel()).dimensions(EmbeddingGateway.DIMENSIONS)
                    .timeout(Duration.ofSeconds(15)).maxRetries(0).logRequests(false).logResponses(false).build();
            int size = model.embed("ping").content().vector().length;
            if (size != EmbeddingGateway.DIMENSIONS)
                return new Part(false, "EMBEDDING_DIMENSION", "The embedding model returned " + size
                        + "-dimensional vectors but the index requires " + EmbeddingGateway.DIMENSIONS);
            return new Part(true, "OK", "Embedding model responded");
        } catch (Exception ex) {
            return failed(ex);
        }
    }

    private static Part failed(Exception ex) {
        var failure = IndexFailureClassifier.classify(ex, "EMBEDDING");
        return new Part(false, failure.code(), failure.message());
    }
}

package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChatModelGateway {
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int maxTokens;
    private volatile StreamingChatModel model;

    public ChatModelGateway(@Value("${repomind.llm.base-url}") String baseUrl,
                            @Value("${repomind.llm.api-key}") String apiKey,
                            @Value("${repomind.llm.model}") String modelName,
                            @Value("${repomind.llm.max-tokens:1024}") int maxTokens,
                            MeterRegistry ignored) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
    }

    public StreamingChatModel model() {
        if (apiKey == null || apiKey.isBlank())
            throw BusinessException.unavailable("LLM_NOT_CONFIGURED", "The language model service is not configured");
        if (model == null) synchronized (this) {
            if (model == null) {
                model = OpenAiStreamingChatModel.builder()
                        .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                        .maxTokens(maxTokens).timeout(Duration.ofSeconds(30))
                        .logRequests(false).logResponses(false).build();
            }
        }
        return model;
    }

    public String modelName() { return modelName; }
}

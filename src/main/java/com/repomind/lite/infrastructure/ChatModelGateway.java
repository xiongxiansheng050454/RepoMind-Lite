package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ChatModelGateway {
    private final LlmSettings settings;
    private volatile LlmSettings.Snapshot builtFor;
    private volatile StreamingChatModel model;

    public ChatModelGateway(LlmSettings settings, MeterRegistry ignored) {
        this.settings = settings;
    }

    public boolean configured() {
        return settings.current().configured();
    }

    public StreamingChatModel model() {
        LlmSettings.Snapshot snapshot = settings.current();
        if (!snapshot.configured())
            throw BusinessException.unavailable("LLM_NOT_CONFIGURED", "The language model service is not configured");
        if (model == null || !snapshot.equals(builtFor)) synchronized (this) {
            if (model == null || !snapshot.equals(builtFor)) {
                model = OpenAiStreamingChatModel.builder()
                        .baseUrl(snapshot.baseUrl()).apiKey(snapshot.apiKey()).modelName(snapshot.model())
                        .maxTokens(snapshot.maxTokens()).timeout(Duration.ofSeconds(120)).returnThinking(true)
                        .logRequests(false).logResponses(false).build();
                builtFor = snapshot;
            }
        }
        return model;
    }

    public String modelName() {
        return settings.current().model();
    }
}

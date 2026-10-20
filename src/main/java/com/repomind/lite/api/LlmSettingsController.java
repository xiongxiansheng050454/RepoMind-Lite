package com.repomind.lite.api;

import com.repomind.lite.infrastructure.LlmConnectionTester;
import com.repomind.lite.infrastructure.LlmSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/llm")
public class LlmSettingsController {
    private final LlmSettings settings;
    private final LlmConnectionTester tester;

    public LlmSettingsController(LlmSettings settings, LlmConnectionTester tester) {
        this.settings = settings;
        this.tester = tester;
    }

    public record SettingsRequest(@Size(max = 512) String baseUrl, @Size(max = 512) String apiKey,
                                  @Size(max = 128) String model, @Size(max = 128) String embeddingModel,
                                  @Min(1) @Max(32768) Integer maxTokens) {
        LlmSettings.Update toUpdate() {
            return new LlmSettings.Update(baseUrl, apiKey, model, embeddingModel, maxTokens);
        }
    }

    public record SettingsView(boolean configured, String baseUrl, String apiKeyHint, String model,
                               String embeddingModel, int maxTokens) {
        static SettingsView of(LlmSettings.Snapshot snapshot) {
            return new SettingsView(snapshot.configured(), snapshot.baseUrl(), LlmSettings.apiKeyHint(snapshot.apiKey()),
                    snapshot.model(), snapshot.embeddingModel(), snapshot.maxTokens());
        }
    }

    @GetMapping
    public SettingsView get() {
        return SettingsView.of(settings.current());
    }

    @PutMapping
    public SettingsView update(@Valid @RequestBody SettingsRequest request) {
        return SettingsView.of(settings.save(request.toUpdate()));
    }

    @PostMapping("/test")
    public LlmConnectionTester.Result test(@Valid @RequestBody SettingsRequest request) {
        return tester.test(settings.resolve(request.toUpdate()));
    }
}

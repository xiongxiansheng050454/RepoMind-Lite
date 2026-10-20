package com.repomind.lite.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repomind.lite.errors.BusinessException;
import org.junit.jupiter.api.Test;

class LlmSettingsTest {
    private static LlmSettings withKey() {
        return LlmSettings.fixed("https://api.openai.com/v1", "sk-existing-key-1234", "gpt-4o-mini", "text-embedding-3-small", 1024);
    }

    @Test
    void blankFieldsKeepCurrentValues() {
        var resolved = withKey().resolve(new LlmSettings.Update(null, "", " ", null, null));
        assertThat(resolved).isEqualTo(withKey().current());
    }

    @Test
    void trailingSlashIsStrippedAndSameEndpointKeepsKey() {
        var resolved = withKey().resolve(new LlmSettings.Update("https://API.openai.com/v1/", null, "gpt-4o", null, 2048));
        assertThat(resolved.baseUrl()).isEqualTo("https://API.openai.com/v1");
        assertThat(resolved.apiKey()).isEqualTo("sk-existing-key-1234");
        assertThat(resolved.model()).isEqualTo("gpt-4o");
        assertThat(resolved.maxTokens()).isEqualTo(2048);
    }

    @Test
    void changingEndpointRequiresReenteringKeySoItIsNeverSentElsewhere() {
        assertThatThrownBy(() -> withKey().resolve(new LlmSettings.Update("https://evil.example/v1", null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo("LLM_SETTINGS_KEY_REQUIRED"));
        var resolved = withKey().resolve(new LlmSettings.Update("https://evil.example/v1", "new-key", null, null, null));
        assertThat(resolved.apiKey()).isEqualTo("new-key");
    }

    @Test
    void unconfiguredSettingsAllowChangingEndpointWithoutKey() {
        var empty = LlmSettings.fixed("https://api.openai.com/v1", "", "m", "e", 1024);
        assertThat(empty.current().configured()).isFalse();
        assertThat(empty.resolve(new LlmSettings.Update("http://localhost:11434/v1", null, null, null, null)).baseUrl())
                .isEqualTo("http://localhost:11434/v1");
    }

    @Test
    void rejectsUnsafeValues() {
        for (String url : new String[]{"ftp://x/v1", "https://user:pw@x/v1", "https://x/v1?k=1", "not a url", "https:///v1"})
            assertThatThrownBy(() -> withKey().resolve(new LlmSettings.Update(url, "k", null, null, null)))
                    .as(url).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> withKey().resolve(new LlmSettings.Update(null, "has space", null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> withKey().resolve(new LlmSettings.Update(null, null, "bad model!", null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> withKey().resolve(new LlmSettings.Update(null, null, null, null, 0)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void snapshotStringAndHintNeverExposeTheKey() {
        assertThat(withKey().current().toString()).doesNotContain("sk-existing").contains("<redacted>");
        assertThat(LlmSettings.apiKeyHint("sk-existing-key-1234")).isEqualTo("••••1234").doesNotContain("existing");
        assertThat(LlmSettings.apiKeyHint("")).isEmpty();
    }
}

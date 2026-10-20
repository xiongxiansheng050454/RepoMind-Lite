package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LlmSettings {
    private static final Logger log = LoggerFactory.getLogger(LlmSettings.class);
    private static final Duration TTL = Duration.ofSeconds(5);
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._:/\\-]{1,128}");

    public record Snapshot(String baseUrl, String apiKey, String model, String embeddingModel, int maxTokens) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Snapshot[baseUrl=" + baseUrl + ", apiKey=<redacted>, model=" + model
                    + ", embeddingModel=" + embeddingModel + ", maxTokens=" + maxTokens + "]";
        }
    }

    public record Update(String baseUrl, String apiKey, String model, String embeddingModel, Integer maxTokens) {
    }

    private final Snapshot defaults;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private volatile Snapshot cached;
    private volatile long loadedAt;

    @Autowired
    public LlmSettings(JdbcTemplate jdbc, TransactionTemplate tx,
                       @Value("${repomind.llm.base-url}") String baseUrl,
                       @Value("${repomind.llm.api-key:}") String apiKey,
                       @Value("${repomind.llm.model}") String model,
                       @Value("${repomind.llm.embedding-model}") String embeddingModel,
                       @Value("${repomind.llm.max-tokens:1024}") int maxTokens) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.defaults = new Snapshot(baseUrl, apiKey, model, embeddingModel, maxTokens);
    }

    private LlmSettings(Snapshot fixed) {
        this.jdbc = null;
        this.tx = null;
        this.defaults = fixed;
    }

    public static LlmSettings fixed(String baseUrl, String apiKey, String model, String embeddingModel, int maxTokens) {
        return new LlmSettings(new Snapshot(baseUrl, apiKey, model, embeddingModel, maxTokens));
    }

    public Snapshot current() {
        if (jdbc == null) return defaults;
        Snapshot snapshot = cached;
        if (snapshot != null && System.nanoTime() - loadedAt < TTL.toNanos()) return snapshot;
        try {
            snapshot = load();
        } catch (RuntimeException ex) {
            log.warn("LLM settings could not be loaded from the database; using {} values ({})",
                    snapshot == null ? "configured default" : "previous", ex.getClass().getSimpleName());
            return snapshot == null ? defaults : snapshot;
        }
        cached = snapshot;
        loadedAt = System.nanoTime();
        return snapshot;
    }

    private Snapshot load() {
        Map<String, String> rows = new HashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM app_settings", rs -> {
            rows.put(rs.getString(1), rs.getString(2));
        });
        int maxTokens = defaults.maxTokens();
        try {
            if (rows.containsKey("llm.max_tokens")) maxTokens = Integer.parseInt(rows.get("llm.max_tokens"));
        } catch (NumberFormatException ignored) {
        }
        return new Snapshot(rows.getOrDefault("llm.base_url", defaults.baseUrl()),
                rows.getOrDefault("llm.api_key", defaults.apiKey()),
                rows.getOrDefault("llm.model", defaults.model()),
                rows.getOrDefault("llm.embedding_model", defaults.embeddingModel()), maxTokens);
    }

    /** Merges an update over the current values and validates the result without persisting it. */
    public Snapshot resolve(Update update) {
        Snapshot current = current();
        String baseUrl = update.baseUrl() == null || update.baseUrl().isBlank()
                ? current.baseUrl() : normalizeBaseUrl(update.baseUrl());
        boolean keyProvided = update.apiKey() != null && !update.apiKey().isBlank();
        String apiKey = keyProvided ? validateKey(update.apiKey()) : current.apiKey();
        if (!keyProvided && current.configured() && !sameEndpoint(baseUrl, current.baseUrl()))
            throw invalid("LLM_SETTINGS_KEY_REQUIRED", "Changing the endpoint requires entering the API key again");
        String model = update.model() == null || update.model().isBlank()
                ? current.model() : validateModel(update.model(), "model");
        String embedding = update.embeddingModel() == null || update.embeddingModel().isBlank()
                ? current.embeddingModel() : validateModel(update.embeddingModel(), "embedding model");
        int maxTokens = update.maxTokens() == null ? current.maxTokens() : update.maxTokens();
        if (maxTokens < 1 || maxTokens > 32768)
            throw invalid("LLM_SETTINGS_INVALID", "Max tokens must be between 1 and 32768");
        return new Snapshot(baseUrl, apiKey, model, embedding, maxTokens);
    }

    public Snapshot save(Update update) {
        if (jdbc == null) throw new IllegalStateException("Settings are read-only in this configuration");
        Snapshot resolved = resolve(update);
        boolean keyProvided = update.apiKey() != null && !update.apiKey().isBlank();
        tx.executeWithoutResult(status -> {
            upsert("llm.base_url", resolved.baseUrl());
            upsert("llm.model", resolved.model());
            upsert("llm.embedding_model", resolved.embeddingModel());
            upsert("llm.max_tokens", Integer.toString(resolved.maxTokens()));
            if (keyProvided) upsert("llm.api_key", resolved.apiKey());
        });
        cached = null;
        return current();
    }

    private void upsert(String key, String value) {
        jdbc.update("INSERT INTO app_settings(setting_key, setting_value) VALUES (?, ?) "
                + "ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = now()", key, value);
    }

    public static String apiKeyHint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        return apiKey.length() <= 8 ? "已设置" : "••••" + apiKey.substring(apiKey.length() - 4);
    }

    private static String normalizeBaseUrl(String value) {
        String text = value.strip();
        if (text.length() > 512) throw invalid("LLM_SETTINGS_INVALID", "Base URL is too long");
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw invalid("LLM_SETTINGS_INVALID", "Base URL must be an http(s) URL without credentials, query or fragment");
        } catch (java.net.URISyntaxException ex) {
            throw invalid("LLM_SETTINGS_INVALID", "Base URL is not a valid URL");
        }
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static boolean sameEndpoint(String left, String right) {
        return left != null && right != null && left.strip().replaceAll("/+$", "").equalsIgnoreCase(right.strip().replaceAll("/+$", ""));
    }

    private static String validateKey(String value) {
        String key = value.strip();
        if (key.length() > 512 || key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw invalid("LLM_SETTINGS_INVALID", "API key must not contain whitespace and must be at most 512 characters");
        return key;
    }

    private static String validateModel(String value, String label) {
        String name = value.strip();
        if (!MODEL.matcher(name).matches())
            throw invalid("LLM_SETTINGS_INVALID", "The " + label + " name contains unsupported characters");
        return name;
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(com.repomind.lite.errors.FailureType.BAD_REQUEST, code, message);
    }
}

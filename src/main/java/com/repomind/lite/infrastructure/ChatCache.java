package com.repomind.lite.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.lite.errors.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ChatCache {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public ChatCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public boolean allow(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) remoteAddress = "unknown";
        try {
            Long count = redis.execute(RATE_SCRIPT, java.util.List.of("repomind:chat:rate:" + remoteAddress), "60");
            if (count == null) throw new IllegalStateException("empty rate-limit response");
            if (count > 10) throw BusinessException.rateLimited("Chat rate limit exceeded; retry later");
            return true;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw BusinessException.unavailable("RATE_LIMIT_UNAVAILABLE", "Request limiting is temporarily unavailable");
        }
    }

    public CachedAnswer get(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : json.readValue(value, CachedAnswer.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public void put(String key, CachedAnswer answer) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(answer), TTL);
        } catch (Exception ignored) {
            // A transient cache outage must not fail an otherwise valid answer.
        }
    }

    public static String key(java.util.UUID repositoryId, String commit, String question, String model) {
        String raw = repositoryId + "\n" + (commit == null ? "" : commit) + "\n" + question.strip().replaceAll("\\s+", " ") + "\n" + model;
        try {
            return "repomind:chat:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record CachedAnswer(String answer, java.util.List<CachedCitation> citations,
                               int inputTokens, int outputTokens) {}

    public record CachedCitation(String path, int startLine, int endLine, String explanation) {}
}

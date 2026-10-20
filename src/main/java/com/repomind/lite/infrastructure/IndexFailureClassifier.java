package com.repomind.lite.infrastructure;

import com.repomind.lite.errors.BusinessException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;

/** Maps indexing and model-call exceptions to stable codes with fixed, secret-free messages. */
public final class IndexFailureClassifier {
    public record Failure(String code, String message, boolean retryable) {
    }

    private IndexFailureClassifier() {
    }

    public static Failure classify(Throwable error, String stage) {
        boolean cloning = "CLONING".equals(stage);
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            Failure match = match(current, cloning);
            if (match != null) return match;
        }
        return switch (stage == null ? "" : stage) {
            case "CLONING" -> of("GIT_CLONE_FAILED", true);
            case "PARSING" -> of("PARSE_FAILED", false);
            case "EMBEDDING" -> of("LLM_ERROR", true);
            default -> of("INDEX_FAILED", true);
        };
    }

    public static String chain(Throwable error) {
        StringBuilder names = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (depth > 0) names.append(" <- ");
            names.append(current.getClass().getSimpleName());
        }
        return names.toString();
    }

    private static Failure match(Throwable t, boolean cloning) {
        if (t instanceof BusinessException ex)
            return new Failure(ex.code(), ex.getMessage(), ex.code().equals("GIT_DNS_FAILED"));
        if (t instanceof AuthenticationException) return of("LLM_AUTH", false);
        if (t instanceof ModelNotFoundException) return of("LLM_MODEL_NOT_FOUND", false);
        if (t instanceof InvalidRequestException) return of("LLM_INVALID_REQUEST", false);
        if (t instanceof RateLimitException) return of("LLM_RATE_LIMITED", true);
        if (t instanceof dev.langchain4j.exception.TimeoutException) return of("LLM_TIMEOUT", true);
        if (t instanceof UnresolvedModelServerException) return of("LLM_UNREACHABLE", false);
        if (t instanceof InternalServerException) return of("LLM_SERVER_ERROR", true);
        if (t instanceof HttpException http) return http(http.statusCode());
        if (t instanceof NoRemoteRepositoryException) return of("GIT_REPO_NOT_FOUND", false);
        if (t instanceof UnknownHostException) return cloning ? of("GIT_DNS_FAILED", true) : of("LLM_UNREACHABLE", true);
        if (t instanceof SSLException) return cloning ? of("GIT_TLS", false) : of("LLM_TLS", false);
        if (t instanceof SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException)
            return cloning ? withTarget("GIT_UNREACHABLE", t) : of("LLM_TIMEOUT", true);
        if (t instanceof ConnectException || t instanceof NoRouteToHostException)
            return cloning ? withTarget("GIT_UNREACHABLE", t) : of("LLM_UNREACHABLE", true);
        return byMessage(t, cloning);
    }

    private static Failure withTarget(String code, Throwable t) {
        String text = t.getMessage();
        if (text == null || !text.startsWith("Connecting to ")) return of(code, true);
        return new Failure(code, message(code) + ": " + text, true);
    }

    private static Failure http(int status) {
        if (status == 401 || status == 403) return of("LLM_AUTH", false);
        if (status == 404) return of("LLM_MODEL_NOT_FOUND", false);
        if (status == 400 || status == 422) return of("LLM_INVALID_REQUEST", false);
        if (status == 429) return of("LLM_RATE_LIMITED", true);
        return status >= 500 ? of("LLM_SERVER_ERROR", true) : of("LLM_ERROR", true);
    }

    private static Failure byMessage(Throwable t, boolean cloning) {
        String text = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        if (text.contains("lease or deadline expired")) return of("INDEX_TIMEOUT", true);
        if (!cloning) return null;
        if (text.contains("private address")) return of("GIT_HOST_PRIVATE_IP", false);
        if (text.contains("redirects are disabled")) return of("GIT_REDIRECT", false);
        if (text.contains("exceeds")) return of("GIT_TOO_LARGE", false);
        if (text.contains("ref could not be resolved")) return of("GIT_REF_NOT_FOUND", false);
        if (t.getClass().getSimpleName().contains("Transport")
                && (text.contains("not authorized") || text.contains("authentication is required")
                || text.contains("not found") || text.contains("404")))
            return of("GIT_REPO_NOT_FOUND", false);
        return null;
    }

    static Failure of(String code, boolean retryable) {
        return new Failure(code, message(code), retryable);
    }

    private static String message(String code) {
        return switch (code) {
            case "GIT_DNS_FAILED" -> "The repository host name could not be resolved";
            case "GIT_UNREACHABLE" -> "The repository host could not be reached";
            case "GIT_TLS" -> "The TLS connection to the repository host failed; its certificate is not trusted by the JVM";
            case "GIT_REPO_NOT_FOUND" -> "The repository was not found or is not public";
            case "GIT_REF_NOT_FOUND" -> "The requested branch, tag or commit was not found";
            case "GIT_HOST_PRIVATE_IP" -> "The repository host resolves to a non-public address";
            case "GIT_REDIRECT" -> "The repository host redirected the request, which is not allowed";
            case "GIT_TOO_LARGE" -> "The repository exceeds the size or file limit";
            case "GIT_CLONE_FAILED" -> "The repository could not be cloned";
            case "PARSE_FAILED" -> "The repository source could not be parsed";
            case "LLM_AUTH" -> "The model service rejected the API key";
            case "LLM_MODEL_NOT_FOUND" -> "The configured model was not found on the model service";
            case "LLM_INVALID_REQUEST" -> "The model service rejected the request; check the model names and parameters";
            case "LLM_RATE_LIMITED" -> "The model service rate limit was reached";
            case "LLM_TIMEOUT" -> "The model service timed out";
            case "LLM_UNREACHABLE" -> "The model service could not be reached";
            case "LLM_TLS" -> "The TLS connection to the model service failed";
            case "LLM_SERVER_ERROR" -> "The model service reported an internal error";
            case "LLM_ERROR" -> "The model service call failed";
            case "INDEX_TIMEOUT" -> "Indexing took too long or its lease expired";
            default -> "Repository indexing could not complete";
        };
    }
}

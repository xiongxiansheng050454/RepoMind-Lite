package com.repomind.lite.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.repomind.lite.errors.BusinessException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class IndexFailureClassifierTest {
    @Test
    void sameDnsErrorMapsToGitOrModelDependingOnStage() {
        var error = new RuntimeException("wrapped", new UnknownHostException("host"));
        assertThat(IndexFailureClassifier.classify(error, "CLONING").code()).isEqualTo("GIT_DNS_FAILED");
        assertThat(IndexFailureClassifier.classify(error, "EMBEDDING").code()).isEqualTo("LLM_UNREACHABLE");
    }

    @Test
    void tlsFailureIsTerminalAndStageSpecific() {
        var error = new IOException("x", new SSLHandshakeException("PKIX path building failed"));
        var git = IndexFailureClassifier.classify(error, "CLONING");
        assertThat(git.code()).isEqualTo("GIT_TLS");
        assertThat(git.retryable()).isFalse();
        assertThat(IndexFailureClassifier.classify(error, "EMBEDDING").code()).isEqualTo("LLM_TLS");
    }

    @Test
    void modelAuthErrorsAreNotRetried() {
        var auth = IndexFailureClassifier.classify(new AuthenticationException("bad key sk-secret"), "EMBEDDING");
        assertThat(auth.code()).isEqualTo("LLM_AUTH");
        assertThat(auth.retryable()).isFalse();
        assertThat(auth.message()).doesNotContain("sk-secret");
        assertThat(IndexFailureClassifier.classify(new HttpException(401, "no"), "EMBEDDING").code()).isEqualTo("LLM_AUTH");
        assertThat(IndexFailureClassifier.classify(new HttpException(404, "no"), "EMBEDDING").code()).isEqualTo("LLM_MODEL_NOT_FOUND");
    }

    @Test
    void transientModelErrorsAreRetried() {
        assertThat(IndexFailureClassifier.classify(new RateLimitException("slow down"), "EMBEDDING").retryable()).isTrue();
        assertThat(IndexFailureClassifier.classify(new HttpException(503, "down"), "EMBEDDING").retryable()).isTrue();
        assertThat(IndexFailureClassifier.classify(new ConnectException("refused"), "CLONING").code()).isEqualTo("GIT_UNREACHABLE");
    }

    @Test
    void unreachableCloneNamesTheDialedAddressButModelCallsStayGeneric() {
        var error = new RuntimeException("wrapped",
                new ConnectException("Connecting to github.com (127.0.0.1:443) failed: Connection refused"));
        var clone = IndexFailureClassifier.classify(error, "CLONING");
        assertThat(clone.code()).isEqualTo("GIT_UNREACHABLE");
        assertThat(clone.message()).contains("github.com (127.0.0.1:443)").contains("Connection refused");
        assertThat(IndexFailureClassifier.classify(new ConnectException("refused"), "CLONING").message())
                .isEqualTo("The repository host could not be reached");
        assertThat(IndexFailureClassifier.classify(error, "EMBEDDING").message())
                .isEqualTo("The model service could not be reached");
    }

    @Test
    void businessExceptionKeepsItsOwnCode() {
        var notConfigured = IndexFailureClassifier.classify(
                BusinessException.unavailable("LLM_NOT_CONFIGURED", "not configured"), "CONFIG");
        assertThat(notConfigured.code()).isEqualTo("LLM_NOT_CONFIGURED");
        assertThat(notConfigured.retryable()).isFalse();
        var dns = IndexFailureClassifier.classify(new BusinessException(
                com.repomind.lite.errors.FailureType.BAD_REQUEST, "GIT_DNS_FAILED", "Unable to resolve hostname: x"), "CLONING");
        assertThat(dns.retryable()).isTrue();
    }

    @Test
    void unknownErrorsFallBackByStageWithoutLeakingMessage() {
        var failure = IndexFailureClassifier.classify(new IOException("secret should not be returned"), "CLONING");
        assertThat(failure.code()).isEqualTo("GIT_CLONE_FAILED");
        assertThat(failure.message()).doesNotContain("secret");
        assertThat(IndexFailureClassifier.classify(new IllegalStateException("boom"), "PARSING").retryable()).isFalse();
    }

    @Test
    void privateAddressAndLimitsAreTerminalWhileCloning() {
        assertThat(IndexFailureClassifier.classify(new IOException("Git remote resolves to a private address"), "CLONING").code())
                .isEqualTo("GIT_HOST_PRIVATE_IP");
        assertThat(IndexFailureClassifier.classify(new IOException("Repository exceeds file limit"), "CLONING").code())
                .isEqualTo("GIT_TOO_LARGE");
    }
}

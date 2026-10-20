package com.repomind.lite.infrastructure;

import com.repomind.lite.domain.RepositoryRecord;
import com.repomind.lite.application.IndexClaim;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit opt-in network test; normal builds never contact public Git providers.
 */
@EnabledIfSystemProperty(named = "repomind.live-git", matches = "true")
class PublicGitSmokeTest {
    @TempDir
    Path directory;

    @Test
    void clonesPublicJavaRepositoryWithVerifiedHttpsAndPinnedCommit() throws Exception {
        var id = UUID.randomUUID();
        var repository = new RepositoryRecord(id,
                "https://github.com/spring-guides/gs-rest-service.git", "HEAD", "QUEUED",
                directory.resolve("checkout"), null, null);
        var claim = new IndexClaim(UUID.randomUUID(), UUID.randomUUID(), repository, 1);
        var git = new GitRepositoryService(new RepositoryUrlValidator(), System.getProperty("repomind.git.proxy", ""));
        var checkout = git.checkout(claim);
        assertThat(checkout.commitSha()).matches("[a-f0-9]{40}");
        assertThat(checkout.defaultBranch()).isNotBlank();
        var extraction = new JavaChunkExtractor().extract(id, checkout.path());
        assertThat(extraction.chunks()).isNotEmpty();
        assertThat(extraction.chunks()).anySatisfy(chunk -> {
            assertThat(chunk.path()).endsWith("GreetingController.java");
            assertThat(chunk.startLine()).isPositive();
            assertThat(chunk.content()).contains("GreetingController");
        });
    }
}

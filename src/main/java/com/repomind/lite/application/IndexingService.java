package com.repomind.lite.application;

import com.repomind.lite.infrastructure.EmbeddingGateway;
import com.repomind.lite.infrastructure.GitRepositoryService;
import com.repomind.lite.infrastructure.IndexStore;
import com.repomind.lite.infrastructure.JavaChunkExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class IndexingService {
    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);
    private final IndexStore store;
    private final GitRepositoryService git;
    private final JavaChunkExtractor extractor;
    private final EmbeddingGateway embeddings;
    private final MeterRegistry meters;

    public IndexingService(IndexStore store, GitRepositoryService git, JavaChunkExtractor extractor,
                           EmbeddingGateway embeddings, MeterRegistry meters) {
        this.store = store; this.git = git; this.extractor = extractor; this.embeddings = embeddings; this.meters = meters;
    }

    public void process(IndexClaim claim) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        MDC.put("repositoryId", claim.repository().id().toString());
        MDC.put("jobId", claim.jobId().toString());
        var sample = Timer.start(meters);
        var heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "index-lease"); thread.setDaemon(true); return thread;
        });
        var expired = new java.util.concurrent.atomic.AtomicBoolean(false);
        heartbeat.scheduleAtFixedRate(() -> { try { if (!store.heartbeat(claim)) expired.set(true); } catch (Exception ignored) { expired.set(true); } }, 15, 15, TimeUnit.SECONDS);
        Instant deadline = Instant.now().plusSeconds(1200);
        try {
            var cloneSample = Timer.start(meters); GitRepositoryService.Checkout checkout;
            try { checkout = git.checkout(claim); } finally { cloneSample.stop(meters.timer("repomind.index.clone.duration")); }
            ensureLease(expired, deadline);
            store.progress(claim, "PARSING", .2, 0, 0, List.of());
            var parseSample = Timer.start(meters); JavaChunkExtractor.Extraction extraction;
            try { extraction = extractor.extract(claim.repository().id(), checkout.path()); } finally { parseSample.stop(meters.timer("repomind.index.parse.duration")); }
            ensureLease(expired, deadline);
            store.progress(claim, "EMBEDDING", .4, extraction.filesProcessed(), extraction.chunks().size(), extraction.warnings());
            var vectors = new ArrayList<float[]>();
            for (int offset = 0; offset < extraction.chunks().size(); offset += 32) {
                ensureLease(expired, deadline); int end = Math.min(offset + 32, extraction.chunks().size());
                vectors.addAll(embeddings.embed(extraction.chunks().subList(offset, end).stream().map(c -> c.content()).toList()));
                store.progress(claim, "EMBEDDING", .4 + .5 * end / Math.max(1.0, extraction.chunks().size()), extraction.filesProcessed(), end, extraction.warnings());
            }
            ensureLease(expired, deadline);
            store.complete(claim, checkout.path(), checkout.commitSha(), checkout.defaultBranch(), extraction.chunks(), vectors, extraction.warnings(), extraction.filesProcessed());
        } catch (Exception ex) {
            meters.counter("repomind.index.failures").increment();
            log.warn("Indexing attempt {} failed for job {} ({})", claim.attempt(), claim.jobId(), ex.getClass().getSimpleName());
            store.fail(claim, "Repository indexing could not complete; check remote availability and model configuration");
        } finally {
            heartbeat.shutdownNow(); sample.stop(meters.timer("repomind.index.duration"));
            if (previousContext == null) MDC.clear(); else MDC.setContextMap(previousContext);
        }
    }

    private static void ensureLease(java.util.concurrent.atomic.AtomicBoolean expired, Instant deadline) {
        if (expired.get() || Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Indexing lease or deadline expired");
    }
}

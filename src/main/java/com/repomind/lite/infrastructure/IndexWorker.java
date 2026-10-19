package com.repomind.lite.infrastructure;

import com.repomind.lite.application.IndexingService;
import com.repomind.lite.application.IndexClaim;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class IndexWorker {
    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);
    private final IndexStore store;
    private final IndexingService indexing;

    @Autowired
    public IndexWorker(IndexStore store, IndexingService indexing) {
        this.store = store;
        this.indexing = indexing;
    }

    /** Compatibility constructor for focused worker tests; production uses the Spring constructor. */
    public IndexWorker(IndexStore store, GitRepositoryService git, JavaChunkExtractor extractor,
                       EmbeddingGateway embeddings, io.micrometer.core.instrument.MeterRegistry meters) {
        this(store, new IndexingService(store, git, extractor, embeddings, meters));
    }

    @RabbitListener(queues = IndexQueue.QUEUE, concurrency = "${repomind.repositories.worker-concurrency:2}")
    public void receive(String value) {
        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (RuntimeException ex) {
            log.warn("Ignoring invalid indexing message");
            return;
        }
        store.claim(id).ifPresent(indexing::process);
    }

    public void process(IndexClaim claim) {
        indexing.process(claim);
    }
}

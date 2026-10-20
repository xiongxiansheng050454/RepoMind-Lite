package com.repomind.lite.infrastructure;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.utility.DockerImageName;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import com.repomind.lite.domain.CodeChunk;
import com.repomind.lite.application.SearchService;
import com.repomind.lite.application.IndexClaim;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class IndexStoreIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management-alpine");
    IndexStore store;
    JdbcTemplate jdbc;
    @TempDir
    Path fixture;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("TRUNCATE repositories CASCADE");
        store = new IndexStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test
    void concurrentSubmissionReusesOneDurableTask() throws Exception {
        var remote = new RepositoryUrlValidator.Remote("https://github.com/acme/demo.git", "HEAD", "test-key");
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<IndexStore.Submission>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> store.submit(remote, Path.of("target/repos")));
            Set<UUID> jobs = new HashSet<>();
            for (var future : pool.invokeAll(calls)) jobs.add(future.get().jobId());
            assertThat(jobs).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM index_outbox", Integer.class)).isEqualTo(1);
            UUID job = jobs.iterator().next();
            assertThat(store.claim(job)).isPresent();
            assertThat(store.claim(job)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retriesFourAttemptsThenCreatesFreshJobOnlyOnResubmission() {
        var remote = new RepositoryUrlValidator.Remote("https://github.com/acme/demo.git", "HEAD", "retry-key");
        var first = store.submit(remote, Path.of("target/repos"));
        for (int i = 0; i < 4; i++) {
            var claim = store.claim(first.jobId()).orElseThrow();
            store.fail(claim, "Indexing could not complete");
        }
        assertThat(store.status(first.repositoryId()).stage()).isEqualTo("FAILED");
        var second = store.submit(remote, Path.of("target/repos"));
        assertThat(second.repositoryId()).isEqualTo(first.repositoryId());
        assertThat(second.jobId()).isNotEqualTo(first.jobId());
    }

    @Test
    void deleteRemovesRepositoryJobsChunksAndOutboxAndIgnoresStaleWorker() {
        var doomed = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/doomed.git", "HEAD", "doomed"), Path.of("target/repos"));
        var kept = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/kept.git", "HEAD", "kept"), Path.of("target/repos"));
        float[] vector = new float[1536];
        vector[0] = 1;
        var chunk = new CodeChunk(UUID.randomUUID(), kept.repositoryId(), "Kept.java", "Kept#run", 1, 1, "class Kept { void run() {} }");
        store.complete(store.claim(kept.jobId()).orElseThrow(), Path.of("target/repos/kept"), "abc", "main", List.of(chunk), List.of(vector), List.of(), 1);
        var staleClaim = store.claim(doomed.jobId()).orElseThrow();

        store.delete(doomed.repositoryId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories WHERE id=?", Integer.class, doomed.repositoryId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM index_jobs WHERE repository_id=?", Integer.class, doomed.repositoryId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM index_outbox WHERE job_id=?", Integer.class, doomed.jobId())).isZero();
        assertThat(store.heartbeat(staleClaim)).isFalse();
        assertThat(store.complete(staleClaim, Path.of("target/repos/doomed"), "x", "main", List.of(), List.of(), List.of(), 0)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repository_id=?", Integer.class, kept.repositoryId())).isEqualTo(1);
        assertThatThrownBy(() -> store.delete(doomed.repositoryId()))
                .isInstanceOf(com.repomind.lite.errors.BusinessException.class);
    }

    @Test
    void expiredLeaseRecoversAndOutboxDeliversDurableMessage() {
        var submission = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/demo.git", "HEAD", "recover-key"), Path.of("target/repos"));
        store.claim(submission.jobId()).orElseThrow();
        jdbc.update("UPDATE index_jobs SET lease_until=now()-interval '1 minute'");
        store.recover();
        assertThat(store.status(submission.repositoryId()).stage()).isEqualTo("QUEUED");
        var factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        try {
            new RabbitAdmin(factory).declareQueue(new Queue(IndexQueue.QUEUE, true));
            var template = new RabbitTemplate(factory);
            new IndexQueue(store, template).publishPending();
            assertThat(template.receiveAndConvert(IndexQueue.QUEUE, 5000)).isEqualTo(submission.jobId().toString());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void readySubmissionReusesJobAndHybridSearchCannotCrossRepositories() {
        var first = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/first.git", "HEAD", "first"), Path.of("target/repos"));
        var second = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/second.git", "HEAD", "second"), Path.of("target/repos"));
        float[] vector = new float[1536];
        vector[0] = 1;
        var firstChunk = new CodeChunk(UUID.randomUUID(), first.repositoryId(), "First.java", "First#findUser", 1, 1, "class First { void findUser() {} }");
        var secondChunk = new CodeChunk(UUID.randomUUID(), second.repositoryId(), "Second.java", "Second#findUser", 1, 1, "class Second { void findUser() {} }");
        var firstClaim = store.claim(first.jobId()).orElseThrow();
        assertThat(store.complete(firstClaim, Path.of("target/repos/first"), "abc", "main", List.of(firstChunk), List.of(vector), List.of(), 1)).isTrue();
        assertThat(store.complete(firstClaim, Path.of("target/repos/first"), "abc", "main", List.of(firstChunk), List.of(vector), List.of(), 1)).isFalse();
        assertThat(store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/first.git", "HEAD", "first"), Path.of("target/repos")).jobId()).isEqualTo(first.jobId());
        store.complete(store.claim(second.jobId()).orElseThrow(), Path.of("target/repos/second"), "def", "main", List.of(secondChunk), List.of(vector), List.of(), 1);
        var service = new SearchService(null, null, new CodeSearchRepository(jdbc), new SimpleMeterRegistry());
        var results = service.searchVector(first.repositoryId(), "findUser", 10, vector);
        assertThat(results).hasSize(1).allSatisfy(hit -> {
            assertThat(hit.chunk().repositoryId()).isEqualTo(first.repositoryId());
            assertThat(hit.score()).isBetween(.7, 1.000001);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repository_id=?", Integer.class, first.repositoryId())).isEqualTo(1);
    }

    @Test
    void rabbitConsumerRunsRealParserAndAtomicallyMarksRepositoryReady() throws Exception {
        Files.writeString(fixture.resolve("Account.java"), "record Account(String name) {\n String display() { return name; }\n}\n");
        var meters = new SimpleMeterRegistry();
        var gitFixture = new GitRepositoryService(new RepositoryUrlValidator()) {
            @Override
            public Checkout checkout(IndexClaim claim) {
                return new Checkout(fixture, "0123456789012345678901234567890123456789", "main");
            }
        };
        var embeddingFixture = new EmbeddingGateway("https://example.com", "fixture", "fixture", meters) {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> {
                    float[] vector = new float[1536];
                    vector[0] = 1;
                    return vector;
                }).toList();
            }
        };
        var worker = new IndexWorker(store, gitFixture, new JavaChunkExtractor(), embeddingFixture, meters);
        var factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        var listener = new SimpleMessageListenerContainer(factory);
        try {
            var admin = new RabbitAdmin(factory);
            admin.declareQueue(new Queue(IndexQueue.QUEUE, true));
            admin.purgeQueue(IndexQueue.QUEUE);
            listener.setQueueNames(IndexQueue.QUEUE);
            listener.setMessageListener(new MessageListenerAdapter(worker, "receive"));
            listener.start();
            var submission = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/fixture.git", "HEAD", "worker-fixture"), fixture);
            new IndexQueue(store, new RabbitTemplate(factory)).publishPending();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> store.status(submission.repositoryId()).stage().equals("READY"));
            var status = store.status(submission.repositoryId());
            assertThat(status.filesProcessed()).isEqualTo(1);
            assertThat(status.chunksCreated()).isEqualTo(2);
            assertThat(status.progress()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repository_id=? AND embedding IS NOT NULL", Integer.class, submission.repositoryId())).isEqualTo(2);
            assertThat(meters.timer("repomind.index.clone.duration").count()).isEqualTo(1);
            assertThat(meters.timer("repomind.index.parse.duration").count()).isEqualTo(1);
        } finally {
            listener.stop();
            factory.destroy();
        }
    }

    @Test
    void workerCountsFailuresAndRestoresLoggingContext() {
        var meters = new SimpleMeterRegistry();
        var unavailableGit = new GitRepositoryService(new RepositoryUrlValidator()) {
            @Override
            public Checkout checkout(IndexClaim claim) throws Exception {
                throw new java.io.IOException("secret should not be returned");
            }
        };
        var worker = new IndexWorker(store, unavailableGit, new JavaChunkExtractor(), null, meters);
        var submission = store.submit(new RepositoryUrlValidator.Remote("https://github.com/acme/failure.git", "HEAD", "worker-failure"), fixture);
        org.slf4j.MDC.put("requestId", "test-request");
        try {
            for (int i = 0; i < 4; i++) worker.process(store.claim(submission.jobId()).orElseThrow());
            assertThat(store.status(submission.repositoryId()).stage()).isEqualTo("FAILED");
            assertThat(store.status(submission.repositoryId()).error()).doesNotContain("secret");
            assertThat(meters.counter("repomind.index.failures").count()).isEqualTo(4);
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("test-request");
            assertThat(org.slf4j.MDC.get("jobId")).isNull();
        } finally {
            org.slf4j.MDC.clear();
        }
    }
}

package com.repomind.lite.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.lite.application.chat.Evidence;
import com.repomind.lite.application.chat.RepositoryAssistant;
import com.repomind.lite.application.chat.RepositoryTools;
import com.repomind.lite.domain.SearchHit;
import com.repomind.lite.errors.ChatFailure;
import com.repomind.lite.infrastructure.ChatModelGateway;
import com.repomind.lite.infrastructure.ChatCache;
import com.repomind.lite.infrastructure.GitRepositoryService;
import dev.langchain4j.service.TokenStream;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class ChatService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatService.class);
    private final RepositoryService repositories;
    private final SearchService search;
    private final GitRepositoryService git;
    private final ChatModelGateway model;
    private final RepositoryAssistant assistant;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final ChatCache cache;
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(5);
    private static final long EMITTER_TIMEOUT_MS = TOTAL_TIMEOUT.toMillis() + 10_000L;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatService(RepositoryService repositories, SearchService search, GitRepositoryService git,
                       ChatModelGateway model, ObjectMapper json, MeterRegistry meters, ChatCache cache) {
        this.repositories = repositories; this.search = search; this.git = git; this.model = model; this.assistant = new com.repomind.lite.infrastructure.LangChainRepositoryAssistant(model);
        this.json = json; this.meters = meters; this.cache = cache;
    }

    ChatService(RepositoryService repositories, SearchService search, GitRepositoryService git,
                ChatModelGateway model, ObjectMapper json, MeterRegistry meters, ChatCache cache, RepositoryAssistant assistant,
                java.util.function.Supplier<dev.langchain4j.model.chat.StreamingChatModel> ignoredModelSupplier) {
        this.repositories = repositories; this.search = search; this.git = git; this.model = model; this.assistant = assistant;
        this.json = json; this.meters = meters; this.cache = cache;
    }

    public SseEmitter chat(UUID repositoryId, String question, String remoteAddress) {
        if (question == null || question.isBlank() || question.length() > 2000)
            throw com.repomind.lite.errors.BusinessException.badRequest("Question must contain 1 to 2000 characters");
        var repository = repositories.requireReady(repositoryId);
        cache.allow(remoteAddress);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        Future<?> task = executor.submit(() -> run(repositoryId, repository, question, emitter, closed));
        emitter.onCompletion(() -> { closed.set(true); task.cancel(true); });
        emitter.onTimeout(() -> {
            log.warn("Chat stream timed out repositoryId={} after {} ms without completing", repositoryId, EMITTER_TIMEOUT_MS);
            fail(emitter, closed, "CHAT_TIMEOUT", "The model did not finish answering in time");
            task.cancel(true);
        });
        emitter.onError(ex -> { log.warn("Chat stream error repositoryId={} error={}", repositoryId, ex.toString()); closed.set(true); task.cancel(true); });
        return emitter;
    }

    private void run(UUID id, com.repomind.lite.domain.RepositoryRecord repository, String question,
                     SseEmitter emitter, AtomicBoolean closed) {
        long started = System.nanoTime();
        Evidence evidence = new Evidence();
        AtomicInteger calls = new AtomicInteger();
        String answer = "";
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        try {
            String cacheKey = ChatCache.key(id, repository.commitSha(), question, model.modelName());
            ChatCache.CachedAnswer cached = cache.get(cacheKey);
            if (cached != null) {
                send(emitter, "token", new Token(cached.answer()));
                cached.citations().forEach(citation -> send(emitter, "citation", new Citation(
                        citation.path(), citation.startLine(), citation.endLine(), citation.explanation())));
                send(emitter, "done", new Done("ok", 0, 0, cached.inputTokens(), cached.outputTokens(), true, false, List.of(), false));
                complete(emitter, closed);
                return;
            }
            send(emitter, "status", new Status("searching", "正在检索相关代码"));
            List<SearchHit> initial = search.search(id, question, 5);
            log.info("Chat search done repositoryId={} hits={} ms={} model={}", id, initial.size(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), model.modelName());
            send(emitter, "status", new Status("waiting_model", "检索到 " + initial.size() + " 段相关代码，等待模型响应"));
            initial.forEach(hit -> evidence.add(hit.chunk().path(), hit.chunk().startLine(), hit.chunk().endLine(),
                    hit.chunk().content(), "Initial search result", Evidence.SourceKind.INITIAL_SEARCH));
            String prompt = prompt(question, initial);
            RepositoryTools tools = new RepositoryTools(id, repository, search, git, json, evidence, calls);
            StringBuilder output = new StringBuilder();
            CountDownLatch done = new CountDownLatch(1);
            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            AtomicInteger partials = new AtomicInteger();
            var modelError = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var finishReason = new java.util.concurrent.atomic.AtomicReference<dev.langchain4j.model.output.FinishReason>();
            TokenStream stream = assistant.answer(prompt, tools)
                    .onPartialThinking(thinking -> {
                        lastActivity.set(System.nanoTime());
                        String text = thinking.text();
                        if (!closed.get() && text != null && !text.isEmpty()) send(emitter, "thinking", new Thinking(text));
                    })
                    .beforeToolExecution(before -> {
                        lastActivity.set(System.nanoTime());
                        var request = before.request();
                        if (!closed.get()) send(emitter, "tool_start", new ToolStart(toolId(request), request.name(),
                                abbreviate(request.arguments(), 400)));
                    })
                    .onPartialResponse(token -> {
                        lastActivity.set(System.nanoTime());
                        int seen = partials.incrementAndGet();
                        if (seen == 1) log.info("Chat first token repositoryId={} afterMs={} blank={} length={}", id,
                                Duration.ofNanos(System.nanoTime() - started).toMillis(), token.isBlank(), token.length());
                        if (!closed.get()) { output.append(token); send(emitter, "token", new Token(token)); }
                    })
                    .onToolExecuted(tool -> {
                        lastActivity.set(System.nanoTime());
                        log.info("Chat tool executed repositoryId={} tool={} afterMs={}", id, tool.request().name(),
                                Duration.ofNanos(System.nanoTime() - started).toMillis());
                        meters.counter("repomind.agent.tool.calls").increment();
                        if (!closed.get()) send(emitter, "tool_end", new ToolEnd(toolId(tool.request()), tool.request().name(),
                                !tool.hasFailed(), tool.result() == null ? 0 : tool.result().length()));
                    })
                    .onCompleteResponse(response -> {
                        finishReason.set(response.finishReason());
                        if (response.metadata().tokenUsage() != null) {
                            var usage = response.metadata().tokenUsage();
                            if (usage.inputTokenCount() != null) inputTokens.set(usage.inputTokenCount());
                            if (usage.outputTokenCount() != null) outputTokens.set(usage.outputTokenCount());
                        }
                        done.countDown();
                    })
                    .onError(error -> { modelError.set(error); done.countDown(); });
            stream.start();
            log.info("Chat model stream started repositoryId={}", id);
            awaitCompletion(done, lastActivity, started);
            log.info("Chat model finished repositoryId={} ms={} partials={} chars={} toolCalls={}", id,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), partials.get(), output.length(), calls.get());
            if (modelError.get() != null) {
                log.warn("Chat model failed repositoryId={} after {} ms error={}", id,
                        Duration.ofNanos(System.nanoTime() - started).toMillis(), modelError.get().toString());
                var failure = com.repomind.lite.infrastructure.IndexFailureClassifier.classify(modelError.get(), "EMBEDDING");
                meters.counter("repomind.sse.errors").increment();
                fail(emitter, closed, failure.code(), failure.message());
                return;
            }
            answer = output.toString();
            Evidence.Result checked = evidence.validate(answer);
            if (!checked.clean()) {
                log.warn("Chat answer has unverified citations repositoryId={} unverified={} uncited={}", id,
                        checked.unverified(), checked.uncited());
            }
            List<Citation> citations = checked.verified().stream().map(source -> new Citation(source.path(), source.startLine(), source.endLine(), source.explanation())).toList();
            for (Citation citation : citations) send(emitter, "citation", citation);
            List<ChatCache.CachedCitation> cachedCitations = citations.stream()
                    .map(citation -> new ChatCache.CachedCitation(citation.path(), citation.startLine(),
                            citation.endLine(), citation.explanation()))
                    .toList();
            if (checked.clean()) {
                cache.put(cacheKey, new ChatCache.CachedAnswer(answer, cachedCitations,
                        inputTokens.get(), outputTokens.get()));
            }
            boolean truncated = finishReason.get() == dev.langchain4j.model.output.FinishReason.LENGTH;
            if (truncated) log.warn("Chat answer truncated by the token limit repositoryId={} chars={}", id, output.length());
            send(emitter, "done", new Done("ok", calls.get(), Duration.ofNanos(System.nanoTime() - started).toMillis(), inputTokens.get(), outputTokens.get(), false, truncated, checked.unverified(), checked.uncited()));
            complete(emitter, closed);
        } catch (ChatFailure ex) { log.warn("Chat failed repositoryId={} after {} ms: {}", id, Duration.ofNanos(System.nanoTime() - started).toMillis(), ex.getMessage()); fail(emitter, closed, "CHAT_FAILED", ex.getMessage()); }
        catch (com.repomind.lite.errors.BusinessException ex) { fail(emitter, closed, ex.code(), ex.getMessage()); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("Chat interrupted repositoryId={} after {} ms (client left or server stopping)", id, Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        catch (Exception ex) {
            log.warn("Chat crashed repositoryId={} after {} ms", id, Duration.ofNanos(System.nanoTime() - started).toMillis(), ex);
            meters.counter("repomind.sse.errors").increment();
            var failure = com.repomind.lite.infrastructure.IndexFailureClassifier.classify(ex, "EMBEDDING");
            if (failure.code().startsWith("LLM_")) fail(emitter, closed, failure.code(), failure.message());
            else fail(emitter, closed, "CHAT_FAILED", "The chat request could not be completed");
        }
    }

    private void awaitCompletion(CountDownLatch done, AtomicLong lastActivity, long started) throws InterruptedException {
        while (!done.await(1, TimeUnit.SECONDS)) {
            long now = System.nanoTime();
            if (now - started > TOTAL_TIMEOUT.toNanos())
                throw new ChatFailure("The answer took longer than " + TOTAL_TIMEOUT.toSeconds() + " seconds");
            if (now - lastActivity.get() > IDLE_TIMEOUT.toNanos())
                throw new ChatFailure("The model produced no output for " + IDLE_TIMEOUT.toSeconds() + " seconds");
        }
    }

    private String prompt(String question, List<SearchHit> hits) {
        StringBuilder prompt = new StringBuilder("Answer only from repository evidence. Cite every factual claim using [path:Lstart-Lend]. If evidence is insufficient, say you cannot confirm it from the repository.\n\nQuestion: ").append(question).append("\n\nInitial evidence:\n");
        for (SearchHit hit : hits) prompt.append("[").append(hit.chunk().path()).append(":L").append(hit.chunk().startLine()).append("-L").append(hit.chunk().endLine()).append("] ").append(hit.chunk().content()).append("\n");
        return prompt.toString();
    }

    private void send(SseEmitter emitter, String name, Object value) {
        try { emitter.send(SseEmitter.event().name(name).data(value)); } catch (Exception ex) { throw new ChatFailure("Client disconnected"); }
    }
    private void complete(SseEmitter emitter, AtomicBoolean closed) { if (closed.compareAndSet(false, true)) emitter.complete(); }
    private void fail(SseEmitter emitter, AtomicBoolean closed, String code, String message) { if (closed.compareAndSet(false, true)) { try { emitter.send(SseEmitter.event().name("error").data(new Error(code, message))); } catch (Exception ignored) {} emitter.complete(); } }

    private static String toolId(dev.langchain4j.agent.tool.ToolExecutionRequest request) {
        String id = request.id();
        if (id != null && !id.isBlank()) return id;
        return request.name() + ":" + Integer.toHexString(String.valueOf(request.arguments()).hashCode());
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public record Token(String text) {}
    public record Thinking(String text) {}
    public record Status(String stage, String detail) {}
    public record ToolStart(String id, String name, String arguments) {}
    public record ToolEnd(String id, String name, boolean ok, int resultChars) {}
    public record Citation(String path, int startLine, int endLine, String explanation) {}
    public record Done(String status, int toolCalls, long durationMs, int inputTokens, int outputTokens, boolean cached, boolean truncated,
                       List<String> unverifiedCitations, boolean uncited) {}
    public record Error(String code, String message) {}
}

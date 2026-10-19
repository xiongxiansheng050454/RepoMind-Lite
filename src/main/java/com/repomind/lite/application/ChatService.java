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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {
    private final RepositoryService repositories;
    private final SearchService search;
    private final GitRepositoryService git;
    private final ChatModelGateway model;
    private final RepositoryAssistant assistant;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final ChatCache cache;
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
        SseEmitter emitter = new SseEmitter(30_000L);
        AtomicBoolean closed = new AtomicBoolean();
        Future<?> task = executor.submit(() -> run(repositoryId, repository, question, emitter, closed));
        emitter.onCompletion(() -> { closed.set(true); task.cancel(true); });
        emitter.onTimeout(() -> { closed.set(true); task.cancel(true); });
        emitter.onError(ex -> { closed.set(true); task.cancel(true); });
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
                send(emitter, "done", new Done("ok", 0, 0, cached.inputTokens(), cached.outputTokens(), true));
                complete(emitter, closed);
                return;
            }
            List<SearchHit> initial = search.search(id, question, 5);
            initial.forEach(hit -> evidence.add(hit.chunk().path(), hit.chunk().startLine(), hit.chunk().endLine(),
                    hit.chunk().content(), "Initial search result", Evidence.SourceKind.INITIAL_SEARCH));
            String prompt = prompt(question, initial);
            RepositoryTools tools = new RepositoryTools(id, repository, search, git, json, evidence, calls);
            StringBuilder output = new StringBuilder();
            CountDownLatch done = new CountDownLatch(1);
            TokenStream stream = assistant.answer(prompt, tools)
                    .onPartialResponse(token -> { if (!closed.get()) { output.append(token); send(emitter, "token", new Token(token)); } })
                    .onToolExecuted(tool -> meters.counter("repomind.agent.tool.calls").increment())
                    .onCompleteResponse(response -> {
                        if (response.metadata().tokenUsage() != null) {
                            var usage = response.metadata().tokenUsage();
                            if (usage.inputTokenCount() != null) inputTokens.set(usage.inputTokenCount());
                            if (usage.outputTokenCount() != null) outputTokens.set(usage.outputTokenCount());
                        }
                        done.countDown();
                    })
                    .onError(error -> done.countDown());
            stream.start();
            if (!done.await(30, TimeUnit.SECONDS)) throw new ChatFailure("Chat request timed out");
            answer = output.toString();
            List<Evidence.Source> cited = evidence.validate(answer);
            List<Citation> citations = cited.stream().map(source -> new Citation(source.path(), source.startLine(), source.endLine(), source.explanation())).toList();
            for (Citation citation : citations) send(emitter, "citation", citation);
            List<ChatCache.CachedCitation> cachedCitations = citations.stream()
                    .map(citation -> new ChatCache.CachedCitation(citation.path(), citation.startLine(),
                            citation.endLine(), citation.explanation()))
                    .toList();
            cache.put(cacheKey, new ChatCache.CachedAnswer(answer, cachedCitations,
                    inputTokens.get(), outputTokens.get()));
            send(emitter, "done", new Done("ok", calls.get(), Duration.ofNanos(System.nanoTime() - started).toMillis(), inputTokens.get(), outputTokens.get(), false));
            complete(emitter, closed);
        } catch (ChatFailure ex) { fail(emitter, closed, "CHAT_FAILED", ex.getMessage()); }
        catch (Exception ex) { meters.counter("repomind.sse.errors").increment(); fail(emitter, closed, "CHAT_FAILED", "The chat request could not be completed"); }
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

    public record Token(String text) {}
    public record Citation(String path, int startLine, int endLine, String explanation) {}
    public record Done(String status, int toolCalls, long durationMs, int inputTokens, int outputTokens, boolean cached) {}
    public record Error(String code, String message) {}
}

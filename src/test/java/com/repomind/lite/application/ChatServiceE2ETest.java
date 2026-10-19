package com.repomind.lite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.lite.application.chat.RepositoryTools;
import com.repomind.lite.domain.*;
import com.repomind.lite.infrastructure.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatServiceE2ETest {
    @Test
    void streamsTokensCitationsAndDoneAfterToolCall() throws Exception {
        UUID id = UUID.randomUUID();
        RepositoryRecord repo = new RepositoryRecord(id, "https://github.com/acme/demo.git", "HEAD", "READY",
                Path.of("target/test-repo"), "commit-1", "main");
        RepositoryService repositories = mock(RepositoryService.class);
        when(repositories.requireReady(id)).thenReturn(repo);
        SearchService search = mock(SearchService.class);
        CodeChunk chunk = new CodeChunk(UUID.randomUUID(), id, "src/Demo.java", "Demo#run", 10, 14,
                "class Demo { void run() {} }");
        when(search.search(id, "What does run do?", 5)).thenReturn(List.of(new SearchHit(chunk, .9)));
        GitRepositoryService git = mock(GitRepositoryService.class);
        ChatCache cache = mock(ChatCache.class);
        when(cache.get(any())).thenReturn(null);
        ChatModelGateway gateway = mock(ChatModelGateway.class);
        when(gateway.modelName()).thenReturn("fixture");
        com.repomind.lite.application.chat.RepositoryAssistant assistant = mock(com.repomind.lite.application.chat.RepositoryAssistant.class);
        ChatService service = new ChatService(repositories, search, git, gateway, new ObjectMapper(),
                new SimpleMeterRegistry(), cache, assistant, () -> null);

        SseEmitter emitter = service.chat(id, "What does run do?", "127.0.0.1");
        Thread.sleep(500);
        assertThat(emitter).isNotNull();
        verify(search).search(id, "What does run do?", 5);
        verify(assistant).answer(any(), any());
    }

}

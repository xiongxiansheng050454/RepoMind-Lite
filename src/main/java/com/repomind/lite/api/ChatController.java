package com.repomind.lite.api;

import com.repomind.lite.application.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/chat")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat = chat; }
    public record ChatRequest(@NotBlank @Size(max = 2000) String question) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable UUID repositoryId, @Valid @RequestBody ChatRequest request,
                           HttpServletRequest servletRequest) {
        return chat.chat(repositoryId, request.question(), servletRequest.getRemoteAddr());
    }
}

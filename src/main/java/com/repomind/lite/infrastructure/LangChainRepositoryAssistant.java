package com.repomind.lite.infrastructure;

import com.repomind.lite.application.chat.RepositoryAssistant;
import com.repomind.lite.application.chat.RepositoryTools;
import com.repomind.lite.errors.ChatFailure;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Component;

@Component
public final class LangChainRepositoryAssistant implements RepositoryAssistant {
    private final ChatModelGateway model;

    public LangChainRepositoryAssistant(ChatModelGateway model) {
        this.model = model;
    }

    @Override
    public TokenStream answer(String prompt, RepositoryTools tools) {
        LangChainApi assistant = AiServices.builder(LangChainApi.class)
                .streamingChatModel(model.model())
                .tools(tools)
                .maxSequentialToolsInvocations(5)
                .toolExecutionErrorHandler((error, context) -> { throw new ChatFailure("Tool execution failed"); })
                .build();
        return assistant.answer(prompt);
    }

    interface LangChainApi {
        TokenStream answer(@UserMessage String prompt);
    }
}

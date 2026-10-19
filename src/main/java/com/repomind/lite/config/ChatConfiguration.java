package com.repomind.lite.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.lite.application.ChatService;
import com.repomind.lite.application.RepositoryService;
import com.repomind.lite.application.SearchService;
import com.repomind.lite.infrastructure.ChatCache;
import com.repomind.lite.infrastructure.ChatModelGateway;
import com.repomind.lite.infrastructure.GitRepositoryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfiguration {

    @Bean
    public ChatService chatService(
            RepositoryService repositories,
            SearchService search,
            GitRepositoryService git,
            ChatModelGateway model,
            ObjectMapper json,
            MeterRegistry meters,
            ChatCache cache) {
        return new ChatService(repositories, search, git, model, json, meters, cache);
    }
}

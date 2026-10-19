package com.repomind.lite.application.chat;

import dev.langchain4j.service.TokenStream;
public interface RepositoryAssistant {
    TokenStream answer(String prompt, RepositoryTools tools);
}

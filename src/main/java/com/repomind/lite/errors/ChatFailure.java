package com.repomind.lite.errors;

/** Failure raised while producing a repository-backed chat response. */
public class ChatFailure extends RuntimeException {
    public ChatFailure(String message) {
        super(message);
    }
}

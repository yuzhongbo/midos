package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;

public record ConversationTurn(String role, String content, Instant createdAt) {

    public static ConversationTurn user(String content) {
        return new ConversationTurn("user", content, Instant.now());
    }

    public static ConversationTurn assistant(String content) {
        return new ConversationTurn("assistant", content, Instant.now());
    }
}


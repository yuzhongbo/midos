package com.zhongbo.mindos.assistant.dispatcher;

import java.util.Locale;

enum DispatcherAnswerMode {
    BALANCED,
    LLM_FIRST;

    static DispatcherAnswerMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return BALANCED;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "llm-first", "llm_first", "llmfirst" -> LLM_FIRST;
            default -> BALANCED;
        };
    }

    boolean llmFirst() {
        return this == LLM_FIRST;
    }
}

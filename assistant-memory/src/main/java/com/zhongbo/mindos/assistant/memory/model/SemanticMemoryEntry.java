package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public record SemanticMemoryEntry(String text, List<Double> embedding, Instant createdAt) {

    public SemanticMemoryEntry {
        embedding = Collections.unmodifiableList(embedding);
    }

    public static SemanticMemoryEntry of(String text, List<Double> embedding) {
        return new SemanticMemoryEntry(text, embedding, Instant.now());
    }
}


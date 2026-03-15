package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record SemanticMemoryEntryDto(String text, List<Double> embedding, Instant createdAt) {

    public SemanticMemoryEntryDto {
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
    }
}


package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VectorMemoryRecord(
        String userId,
        String content,
        List<Double> embedding,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public VectorMemoryRecord {
        embedding = Collections.unmodifiableList(embedding);
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static VectorMemoryRecord of(String userId,
                                        String content,
                                        List<Double> embedding,
                                        Map<String, Object> metadata) {
        return new VectorMemoryRecord(userId, content, embedding, metadata, Instant.now());
    }
}


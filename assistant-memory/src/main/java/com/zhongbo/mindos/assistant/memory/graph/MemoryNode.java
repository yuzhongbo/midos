package com.zhongbo.mindos.assistant.memory.graph;

import java.time.Instant;
import java.util.Map;

public record MemoryNode(String id,
                         String type,
                         Map<String, Object> data,
                         Instant createdAt,
                         Instant updatedAt) {

    public MemoryNode {
        id = id == null ? "" : id.trim();
        type = type == null ? "" : type.trim();
        data = data == null ? Map.of() : Map.copyOf(data);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public String name() {
        Object value = data.get("name");
        return value == null ? "" : String.valueOf(value).trim();
    }

    public MemoryNode touch(Map<String, Object> mergedData) {
        return new MemoryNode(id, type, mergedData, createdAt, Instant.now());
    }
}

package com.zhongbo.mindos.assistant.memory.graph;

import java.time.Instant;
import java.util.Map;

public record GraphMemoryNode(String id,
                              String type,
                              String name,
                              Map<String, Object> attributes,
                              Instant createdAt,
                              Instant updatedAt) {

    public GraphMemoryNode {
        id = id == null ? "" : id.trim();
        type = type == null ? "" : type.trim();
        name = name == null ? "" : name.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public GraphMemoryNode touch(Map<String, Object> mergedAttributes) {
        return new GraphMemoryNode(id, type, name, mergedAttributes, createdAt, Instant.now());
    }
}

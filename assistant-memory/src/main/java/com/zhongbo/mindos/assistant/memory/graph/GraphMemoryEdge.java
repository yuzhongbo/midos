package com.zhongbo.mindos.assistant.memory.graph;

import java.time.Instant;
import java.util.Map;

public record GraphMemoryEdge(String sourceId,
                              String relation,
                              String targetId,
                              double weight,
                              Map<String, Object> attributes,
                              Instant createdAt) {

    public GraphMemoryEdge {
        sourceId = sourceId == null ? "" : sourceId.trim();
        relation = relation == null ? "" : relation.trim();
        targetId = targetId == null ? "" : targetId.trim();
        weight = Math.max(0.0, weight);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String key() {
        return sourceId + "->" + relation + "->" + targetId;
    }
}

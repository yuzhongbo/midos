package com.zhongbo.mindos.assistant.memory.graph;

import java.time.Instant;
import java.util.Map;

public record MemoryEdge(String from,
                         String to,
                         String relation,
                         double weight,
                         Map<String, Object> data,
                         Instant createdAt) {

    public MemoryEdge {
        from = from == null ? "" : from.trim();
        to = to == null ? "" : to.trim();
        relation = relation == null ? "" : relation.trim();
        weight = Math.max(0.0, weight);
        data = data == null ? Map.of() : Map.copyOf(data);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String key() {
        return from + "->" + relation + "->" + to;
    }
}

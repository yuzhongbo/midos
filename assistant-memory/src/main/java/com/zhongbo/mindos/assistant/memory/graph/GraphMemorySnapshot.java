package com.zhongbo.mindos.assistant.memory.graph;

import java.util.List;

public record GraphMemorySnapshot(List<MemoryNode> nodes, List<MemoryEdge> edges) {

    public GraphMemorySnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static GraphMemorySnapshot empty() {
        return new GraphMemorySnapshot(List.of(), List.of());
    }
}

package com.zhongbo.mindos.assistant.memory.graph;

import java.util.List;

public record GraphMemorySnapshot(List<GraphMemoryNode> nodes, List<GraphMemoryEdge> edges) {

    public GraphMemorySnapshot {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static GraphMemorySnapshot empty() {
        return new GraphMemorySnapshot(List.of(), List.of());
    }
}

package com.zhongbo.mindos.assistant.memory.graph;

import java.util.List;
import java.util.Map;

public interface GraphMemoryView {

    GraphMemorySnapshot snapshot(String userId);

    List<MemoryNode> searchNodes(String userId, String keyword, int limit);

    default Map<String, Double> scoreCandidates(String userId, String userInput, List<String> candidateNames) {
        return Map.of();
    }

    List<MemoryEdge> outgoingEdges(String userId, String nodeId);

    GraphMemorySnapshot traverse(String userId, GraphMemoryQuery query);
}

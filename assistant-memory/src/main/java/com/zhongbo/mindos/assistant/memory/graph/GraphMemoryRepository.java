package com.zhongbo.mindos.assistant.memory.graph;

import java.util.List;
import java.util.Optional;

public interface GraphMemoryRepository {

    GraphMemoryNode saveNode(String userId, GraphMemoryNode node);

    GraphMemoryEdge saveEdge(String userId, GraphMemoryEdge edge);

    Optional<GraphMemoryNode> findNode(String userId, String nodeId);

    List<GraphMemoryNode> listNodes(String userId);

    List<GraphMemoryEdge> listEdges(String userId);

    List<GraphMemoryEdge> outgoingEdges(String userId, String nodeId);
}

package com.zhongbo.mindos.assistant.memory.graph;

import java.util.List;
import java.util.Optional;

public interface GraphMemoryRepository {

    MemoryNode saveNode(String userId, MemoryNode node);

    MemoryEdge saveEdge(String userId, MemoryEdge edge);

    boolean deleteNode(String userId, String nodeId);

    Optional<MemoryNode> findNode(String userId, String nodeId);

    List<MemoryNode> listNodes(String userId);

    List<MemoryEdge> listEdges(String userId);

    List<MemoryEdge> outgoingEdges(String userId, String nodeId);
}

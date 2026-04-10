package com.zhongbo.mindos.assistant.memory.graph;

import java.util.List;

public interface GraphMemoryView {

    GraphMemorySnapshot snapshot(String userId);

    List<MemoryNode> searchNodes(String userId, String keyword, int limit);

    List<MemoryEdge> outgoingEdges(String userId, String nodeId);

    GraphMemorySnapshot traverse(String userId, GraphMemoryQuery query);
}

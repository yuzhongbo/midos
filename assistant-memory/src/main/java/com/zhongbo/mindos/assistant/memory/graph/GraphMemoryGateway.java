package com.zhongbo.mindos.assistant.memory.graph;

import java.util.Map;

public interface GraphMemoryGateway {

    GraphMemoryNode upsertNode(String userId, GraphMemoryNode node);

    GraphMemoryEdge upsertEdge(String userId, GraphMemoryEdge edge);

    default GraphMemoryEdge link(String userId,
                                 String sourceId,
                                 String relation,
                                 String targetId,
                                 double weight,
                                 Map<String, Object> attributes) {
        return upsertEdge(userId, new GraphMemoryEdge(sourceId, relation, targetId, weight, attributes, null));
    }
}

package com.zhongbo.mindos.assistant.memory.graph;

import java.util.Map;

public interface GraphMemoryGateway {

    MemoryNode upsertNode(String userId, MemoryNode node);

    MemoryEdge upsertEdge(String userId, MemoryEdge edge);

    default MemoryEdge link(String userId,
                            String from,
                            String relation,
                            String to,
                            double weight,
                            Map<String, Object> data) {
        return upsertEdge(userId, new MemoryEdge(from, to, relation, weight, data, null));
    }
}

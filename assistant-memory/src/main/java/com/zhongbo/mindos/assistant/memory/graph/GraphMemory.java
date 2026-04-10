package com.zhongbo.mindos.assistant.memory.graph;

import org.springframework.stereotype.Service;

@Service
public class GraphMemory extends GraphMemoryService {

    public GraphMemory() {
        super();
    }

    public GraphMemory(GraphMemoryRepository repository) {
        super(repository);
    }
}

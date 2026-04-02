package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRouterTest {

    @Test
    void shouldRouteToAllConfiguredLayers() {
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(new MemoryConsolidationService());
        MemoryRouter router = new MemoryRouter(
                new BufferMemoryService(5),
                new WorkingMemoryService(),
                semanticMemoryService,
                new FactMemoryService()
        );

        router.save(new MemoryRecord(null, "router-user", "latest raw message", MemoryLayer.BUFFER, null, Map.of("sessionId", "s1"), 0.8, null, null));
        router.save(new MemoryRecord(null, "router-user", "current task state", MemoryLayer.WORKING, null, Map.of("sessionId", "s1", "topic", "todo"), 0.9, null, null));
        router.save(new MemoryRecord(null, "router-user", "weekly summary", MemoryLayer.SEMANTIC, null, Map.of("topic", "weekly"), 0.7, null, null));
        router.save(new MemoryRecord(null, "router-user", "", MemoryLayer.FACT, null, Map.of("subject", "alice", "predicate", "owns", "object", "project-x"), 1.0, null, null));

        List<MemoryRecord> semanticResults = router.query(MemoryQuery.builder()
                .userId("router-user")
                .layer(MemoryLayer.SEMANTIC)
                .topic("weekly")
                .build());
        List<MemoryRecord> allResults = router.query(MemoryQuery.builder()
                .userId("router-user")
                .limit(10)
                .build());

        assertEquals(1, semanticResults.size());
        assertEquals("weekly summary", semanticResults.get(0).content());
        assertEquals(4, allResults.size());
        assertTrue(allResults.stream().anyMatch(record -> record.layer() == MemoryLayer.FACT && record.content().contains("alice owns project-x")));
    }
}

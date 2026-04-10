package com.zhongbo.mindos.assistant.memory.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMemoryServiceTest {

    @Test
    void shouldSearchAndTraverseGraph() {
        GraphMemoryService service = new GraphMemoryService();
        service.upsertNode("u1", new GraphMemoryNode("student:1", "entity.student", "Alice", Map.of("skillName", "student.get"), null, null));
        service.upsertNode("u1", new GraphMemoryNode("skill:analyze", "skill", "student.analyze", Map.of("intent", "student.plan"), null, null));
        service.upsertEdge("u1", new GraphMemoryEdge("student:1", "related-to", "skill:analyze", 0.9, Map.of("reason", "latest workflow"), null));

        assertEquals(1, service.searchNodes("u1", "Alice", 5).size());

        GraphMemorySnapshot snapshot = service.traverse("u1", GraphMemoryQuery.traverse(Set.of("student:1"), 2, 10));
        assertEquals(2, snapshot.nodes().size());
        assertEquals(1, snapshot.edges().size());
        assertFalse(snapshot.edges().isEmpty());
        assertTrue(snapshot.edges().get(0).relation().contains("related"));
    }
}

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
        GraphMemory service = new GraphMemory();
        service.addNode("u1", new MemoryNode("student:1", "entity.student", Map.of("name", "Alice", "studentId", "stu-1", "skillName", "student.get"), null, null));
        service.addNode("u1", new MemoryNode("skill:analyze", "skill", Map.of("name", "student.analyze", "intent", "student.plan"), null, null));
        service.addEdge("u1", new MemoryEdge("student:1", "skill:analyze", "related-to", 0.9, Map.of("reason", "latest workflow"), null));

        assertEquals(1, service.searchNodes("u1", "Alice", 5).size());

        GraphMemorySnapshot snapshot = service.traverse("u1", GraphMemoryQuery.traverse(Set.of("student:1"), 2, 10));
        assertEquals(2, snapshot.nodes().size());
        assertEquals(1, snapshot.edges().size());
        assertFalse(snapshot.edges().isEmpty());
        assertTrue(snapshot.edges().get(0).relation().contains("related"));
    }

    @Test
    void shouldInferStudentIdFromEntityEventAndResultGraph() {
        GraphMemory service = new GraphMemory();
        service.addNode("u1", new MemoryNode("entity:student:42", "entity.student", Map.of("name", "Alice", "studentId", "stu-42"), null, null));
        service.addNode("u1", new MemoryNode("event:assessment:1", "event.assessment", Map.of("name", "latest-assessment", "topic", "math"), null, null));
        service.addNode("u1", new MemoryNode("result:analysis:1", "result.analysis", Map.of("name", "latest-analysis"), null, null));
        service.addEdge("u1", new MemoryEdge("event:assessment:1", "entity:student:42", "about-student", 0.9, Map.of(), null));
        service.addEdge("u1", new MemoryEdge("result:analysis:1", "event:assessment:1", "derived-from", 0.8, Map.of(), null));

        assertEquals("stu-42", service.infer("u1", "studentId", "Alice math").orElse(null));
        assertEquals(1, service.queryRelated("u1", "event:assessment:1", "about-student").size());
    }
}

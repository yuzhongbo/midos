package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.VectorMemoryRecord;
import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryFacadeTest {

    @Test
    void shouldPreferGraphThenVectorThenDefault() {
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new MemoryNode("entity:student:1", "entity.student", Map.of("studentId", "stu-1"), null, null));

        VectorMemory vectorMemory = new VectorMemory() {
            @Override
            public List<Double> embed(String text) {
                return List.of(0.1, 0.2, 0.3);
            }

            @Override
            public List<VectorSearchResult> search(String userId, String query, int topK) {
                return List.of(new VectorSearchResult(
                        VectorMemoryRecord.of(userId, "student profile stu-9", List.of(0.2, 0.3, 0.4), Map.of("studentId", "stu-9")),
                        0.88
                ));
            }
        };

        MemoryFacade graphFirst = new MemoryFacade(graphMemory, vectorMemory);
        assertEquals("stu-1", graphFirst.infer("u1", "studentId", "Alice").orElse(null));

        MemoryFacade vectorSecond = new MemoryFacade(new GraphMemory(), vectorMemory);
        assertEquals("stu-9", vectorSecond.infer("u1", "studentId", "Alice").orElse(null));

        MemoryFacade defaultFallback = new MemoryFacade(new GraphMemory(), queryVector());
        assertEquals("stu-default", defaultFallback.infer("u1", "studentId", "Alice", () -> Optional.of("stu-default")).orElse(null));
    }

    private VectorMemory queryVector() {
        return new VectorMemory() {
            @Override
            public List<Double> embed(String text) {
                return List.of();
            }

            @Override
            public List<VectorSearchResult> search(String userId, String query, int topK) {
                return List.of();
            }
        };
    }
}

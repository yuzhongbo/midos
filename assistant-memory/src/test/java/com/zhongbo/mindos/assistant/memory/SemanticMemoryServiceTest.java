package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticMemoryServiceTest {

    @Test
    void shouldPreferSameBucketResultsWhenSearching() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        SemanticMemoryService service = new SemanticMemoryService(consolidationService);

        service.addEntry("bucket-user", SemanticMemoryEntry.of("数学考试复习计划", List.of(0.1, 0.2)), "learning");
        service.addEntry("bucket-user", SemanticMemoryEntry.of("数学考试复习计划", List.of(0.1, 0.2)), "task");

        List<SemanticMemoryEntry> learning = service.search("bucket-user", "数学复习", 1, "learning");
        List<SemanticMemoryEntry> task = service.search("bucket-user", "数学复习", 1, "task");

        assertEquals(1, learning.size());
        assertEquals("数学考试复习计划", learning.get(0).text());
        assertEquals(1, task.size());
        assertEquals("数学考试复习计划", task.get(0).text());
    }

    @Test
    void shouldAllowSameTextInDifferentBuckets() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        SemanticMemoryService service = new SemanticMemoryService(consolidationService);

        service.addEntry("bucket-user", SemanticMemoryEntry.of("准备周会汇报", List.of(0.1, 0.2)), "task");
        service.addEntry("bucket-user", SemanticMemoryEntry.of("准备周会汇报", List.of(0.1, 0.2)), "general");

        List<SemanticMemoryEntry> results = service.search("bucket-user", "周会", 10, "task");
        assertEquals(2, results.size());
    }
}


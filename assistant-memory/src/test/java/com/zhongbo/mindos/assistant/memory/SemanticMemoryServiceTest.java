package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldCapCrossBucketFallbackWhenPreferredBucketIsExplicit() {
        String oldMax = System.getProperty("mindos.memory.search.cross-bucket.max");
        String oldRatio = System.getProperty("mindos.memory.search.cross-bucket.ratio");
        try {
            System.setProperty("mindos.memory.search.cross-bucket.max", "1");
            System.setProperty("mindos.memory.search.cross-bucket.ratio", "1.0");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            service.addEntry("mix-user", SemanticMemoryEntry.of("数学复习任务安排", List.of(0.1, 0.2)), "task");
            service.addEntry("mix-user", SemanticMemoryEntry.of("数学复习错题整理", List.of(0.1, 0.2)), "task");
            service.addEntry("mix-user", SemanticMemoryEntry.of("数学复习知识点总结", List.of(0.1, 0.2)), "learning");
            service.addEntry("mix-user", SemanticMemoryEntry.of("数学复习概念梳理", List.of(0.1, 0.2)), "learning");

            List<SemanticMemoryEntry> results = service.search("mix-user", "数学复习", 4, "task");
            assertEquals(3, results.size());
            assertTrue(results.get(0).text().contains("任务") || results.get(0).text().contains("错题"));
            assertTrue(results.get(1).text().contains("任务") || results.get(1).text().contains("错题"));

            long crossCount = results.stream()
                    .map(SemanticMemoryEntry::text)
                    .filter(text -> text.contains("知识点") || text.contains("概念"))
                    .count();
            assertEquals(1, crossCount);
        } finally {
            restoreProperty("mindos.memory.search.cross-bucket.max", oldMax);
            restoreProperty("mindos.memory.search.cross-bucket.ratio", oldRatio);
        }
    }

    @Test
    void shouldNotApplyCrossBucketCapWithoutExplicitPreferredBucket() {
        String oldMax = System.getProperty("mindos.memory.search.cross-bucket.max");
        String oldRatio = System.getProperty("mindos.memory.search.cross-bucket.ratio");
        try {
            System.setProperty("mindos.memory.search.cross-bucket.max", "1");
            System.setProperty("mindos.memory.search.cross-bucket.ratio", "0.25");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            service.addEntry("plain-user", SemanticMemoryEntry.of("数学复习任务安排", List.of(0.1, 0.2)), "task");
            service.addEntry("plain-user", SemanticMemoryEntry.of("数学复习错题整理", List.of(0.1, 0.2)), "task");
            service.addEntry("plain-user", SemanticMemoryEntry.of("数学复习知识点总结", List.of(0.1, 0.2)), "learning");
            service.addEntry("plain-user", SemanticMemoryEntry.of("数学复习概念梳理", List.of(0.1, 0.2)), "learning");

            List<SemanticMemoryEntry> results = service.search("plain-user", "数学复习", 4);
            assertEquals(4, results.size());
        } finally {
            restoreProperty("mindos.memory.search.cross-bucket.max", oldMax);
            restoreProperty("mindos.memory.search.cross-bucket.ratio", oldRatio);
        }
    }

    @Test
    void shouldRespectCrossBucketRatioCapWhenPreferredBucketHasNoMatches() {
        String oldMax = System.getProperty("mindos.memory.search.cross-bucket.max");
        String oldRatio = System.getProperty("mindos.memory.search.cross-bucket.ratio");
        try {
            System.setProperty("mindos.memory.search.cross-bucket.max", "3");
            System.setProperty("mindos.memory.search.cross-bucket.ratio", "0.25");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            service.addEntry("ratio-user", SemanticMemoryEntry.of("数学复习知识点总结", List.of(0.1, 0.2)), "learning");
            service.addEntry("ratio-user", SemanticMemoryEntry.of("数学复习概念梳理", List.of(0.1, 0.2)), "learning");
            service.addEntry("ratio-user", SemanticMemoryEntry.of("数学复习公式清单", List.of(0.1, 0.2)), "learning");
            service.addEntry("ratio-user", SemanticMemoryEntry.of("数学复习重点章节", List.of(0.1, 0.2)), "learning");

            List<SemanticMemoryEntry> results = service.search("ratio-user", "数学复习", 4, "task");
            assertEquals(1, results.size());
            String texts = results.stream().map(SemanticMemoryEntry::text).collect(Collectors.joining(","));
            assertTrue(!texts.isBlank());
        } finally {
            restoreProperty("mindos.memory.search.cross-bucket.max", oldMax);
            restoreProperty("mindos.memory.search.cross-bucket.ratio", oldRatio);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}


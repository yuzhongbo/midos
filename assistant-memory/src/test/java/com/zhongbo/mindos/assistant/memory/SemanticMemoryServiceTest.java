package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertFalse(texts.isBlank());
        } finally {
            restoreProperty("mindos.memory.search.cross-bucket.max", oldMax);
            restoreProperty("mindos.memory.search.cross-bucket.ratio", oldRatio);
        }
    }

    @Test
    void shouldSearchLargeDatasetWithoutFullSortRegression() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        SemanticMemoryService service = new SemanticMemoryService(consolidationService);

        for (int i = 0; i < 6_000; i++) {
            String bucket = i % 3 == 0 ? "task" : (i % 3 == 1 ? "learning" : "global");
            service.addEntry("large-search-user",
                    SemanticMemoryEntry.of("项目计划条目 " + i + " 涉及里程碑 review", List.of(0.1 + i, 0.2 + i)),
                    bucket);
        }

        List<SemanticMemoryEntry> results = service.search("large-search-user", "项目计划 review", 20);
        assertEquals(20, results.size());
        assertTrue(results.get(0).text().contains("项目计划条目"));
    }

    @Test
    void shouldRemainConsistentUnderConcurrentWriteAndSearch() throws Exception {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        SemanticMemoryService service = new SemanticMemoryService(consolidationService);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int writer = 0; writer < 4; writer++) {
            int worker = writer;
            tasks.add(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 500; i++) {
                    String text = "并发语义-" + worker + "-" + i + " deadline 2026-12-31";
                    service.addEntry("concurrent-semantic-user", SemanticMemoryEntry.of(text, List.of(0.1 + i, 0.2 + i)), "task");
                }
                return null;
            });
        }

        for (int searcher = 0; searcher < 2; searcher++) {
            tasks.add(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 200; i++) {
                    List<SemanticMemoryEntry> results = service.search("concurrent-semantic-user", "并发语义 deadline", 10, "task");
                    assertTrue(results.size() <= 10);
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();

        for (Future<Void> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertFalse(service.search("concurrent-semantic-user", 10).isEmpty());
    }

    @Test
    void shouldApplySecondaryDuplicateGateWhenEnabled() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.semantic-duplicate.enabled");
        String oldThreshold = System.getProperty("mindos.memory.write-gate.semantic-duplicate.threshold");
        try {
            System.setProperty("mindos.memory.write-gate.semantic-duplicate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.semantic-duplicate.threshold", "0.70");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            service.addEntry("dup-gate-user",
                    SemanticMemoryEntry.of("project alpha finalize api integration and regression test", List.of(0.11, 0.22)),
                    "task");
            service.addEntry("dup-gate-user",
                    SemanticMemoryEntry.of("project alpha finalize api integration regression test", List.of(0.12, 0.21)),
                    "task");

            List<SemanticMemoryEntry> results = service.search("dup-gate-user", "project alpha", 10, "task");
            assertEquals(1, results.size());
        } finally {
            restoreProperty("mindos.memory.write-gate.semantic-duplicate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.semantic-duplicate.threshold", oldThreshold);
        }
    }

    @Test
    void shouldKeepTopKStableWithCoarseCandidateCap() {
        String oldMin = System.getProperty("mindos.memory.search.coarse.min-candidates");
        String oldMultiplier = System.getProperty("mindos.memory.search.coarse.multiplier");
        try {
            System.setProperty("mindos.memory.search.coarse.min-candidates", "6");
            System.setProperty("mindos.memory.search.coarse.multiplier", "2");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            for (int i = 0; i < 30; i++) {
                service.addEntry("coarse-user",
                        SemanticMemoryEntry.of("alpha topic backlog item " + i, List.of(0.1 + i, 0.2 + i)),
                        "task");
            }
            service.addEntry("coarse-user",
                    SemanticMemoryEntry.of("alpha topic critical launch checklist", List.of(9.9, 9.8)),
                    "task");

            List<SemanticMemoryEntry> results = service.search("coarse-user", "alpha topic critical", 3, "task");
            assertEquals(3, results.size());
            assertTrue(results.stream().anyMatch(item -> item.text().contains("critical launch checklist")));
        } finally {
            restoreProperty("mindos.memory.search.coarse.min-candidates", oldMin);
            restoreProperty("mindos.memory.search.coarse.multiplier", oldMultiplier);
        }
    }

    @Test
    void shouldExposeSecondaryDuplicateGateMetrics() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.semantic-duplicate.enabled");
        String oldThreshold = System.getProperty("mindos.memory.write-gate.semantic-duplicate.threshold");
        try {
            System.setProperty("mindos.memory.write-gate.semantic-duplicate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.semantic-duplicate.threshold", "0.7");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);

            service.addEntry("metrics-user", SemanticMemoryEntry.of("alpha beta gamma sprint task", List.of(0.1, 0.2)), "task");
            service.addEntry("metrics-user", SemanticMemoryEntry.of("alpha beta sprint task", List.of(0.11, 0.19)), "task");
            service.addEntry("metrics-user", SemanticMemoryEntry.of("release checklist owner and due date", List.of(0.2, 0.4)), "task");

            var metrics = service.snapshotWriteGateMetrics();
            assertTrue(metrics.secondaryDuplicateGateEnabled());
            assertEquals(3, metrics.secondaryDuplicateChecks());
            assertEquals(1, metrics.secondaryDuplicateIntercepted());
            assertEquals(1.0 / 3.0, metrics.secondaryDuplicateInterceptRate());
        } finally {
            restoreProperty("mindos.memory.write-gate.semantic-duplicate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.semantic-duplicate.threshold", oldThreshold);
        }
    }

    @Test
    void shouldGenerateLocalEmbeddingAndHybridScoresWhenConfigured() {
        String oldHybridEnabled = System.getProperty("mindos.memory.search.hybrid.enabled");
        String oldEmbeddingEnabled = System.getProperty("mindos.memory.embedding.local.enabled");
        try {
            System.setProperty("mindos.memory.search.hybrid.enabled", "true");
            System.setProperty("mindos.memory.embedding.local.enabled", "true");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            service.addEntry("hybrid-user", SemanticMemoryEntry.of("project alpha owner alice due friday", List.of()), "task");

            List<RankedSemanticMemory> results = service.searchDetailed("hybrid-user", "alpha owner", 3, "task");

            assertEquals(1, results.size());
            assertFalse(results.get(0).entry().text().isBlank());
            assertFalse(results.get(0).entry().embedding().isEmpty());
            assertTrue(results.get(0).lexicalScore() > 0.0);
            assertTrue(results.get(0).vectorScore() > 0.0);

            List<Double> existingEmbedding = List.of(0.9, 0.8, 0.7);
            service.addEntry("hybrid-user", SemanticMemoryEntry.of("project beta stable vector", existingEmbedding), "task");
            List<SemanticMemoryEntry> preserved = service.search("hybrid-user", "beta stable", 5, "task");
            assertTrue(preserved.stream().anyMatch(item -> item.embedding().equals(existingEmbedding)));
        } finally {
            restoreProperty("mindos.memory.search.hybrid.enabled", oldHybridEnabled);
            restoreProperty("mindos.memory.embedding.local.enabled", oldEmbeddingEnabled);
        }
    }

    @Test
    void shouldClassifyFactLayerForDenseKeySignalEntries() {
        String oldLayersEnabled = System.getProperty("mindos.memory.layers.enabled");
        String oldFactMaxChars = System.getProperty("mindos.memory.layers.fact-max-chars");
        try {
            System.setProperty("mindos.memory.layers.enabled", "true");
            System.setProperty("mindos.memory.layers.fact-max-chars", "120");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            SemanticMemoryService service = new SemanticMemoryService(consolidationService);
            service.addEntry("layer-user",
                    new SemanticMemoryEntry("API owner Alice due 2026-04-05", List.of(0.1, 0.2), java.time.Instant.now()),
                    "task");
            service.addEntry("layer-user",
                    new SemanticMemoryEntry("A long project retrospective about collaboration rituals and meeting cadence",
                            List.of(0.2, 0.3),
                            java.time.Instant.now().minusSeconds(3600L * 24 * 10)),
                    "learning");

            List<RankedSemanticMemory> results = service.searchDetailed("layer-user", "owner due", 5, null);

            assertTrue(results.stream().anyMatch(item -> item.layer() == MemoryLayer.FACT));
        } finally {
            restoreProperty("mindos.memory.layers.enabled", oldLayersEnabled);
            restoreProperty("mindos.memory.layers.fact-max-chars", oldFactMaxChars);
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

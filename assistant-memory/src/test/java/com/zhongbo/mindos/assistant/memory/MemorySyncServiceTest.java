package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySyncServiceTest {
    @Test
    void shouldSkipShortLowSignalSemanticEntryWhenWriteGateEnabled() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.enabled");
        String oldMinLength = System.getProperty("mindos.memory.write-gate.min-length");
        try {
            System.setProperty("mindos.memory.write-gate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.min-length", "12");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
            EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
            SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
            ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
            SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
            MemorySyncService service = new MemorySyncService(
                    repository,
                    episodicMemoryService,
                    semanticMemoryService,
                    proceduralMemoryService,
                    consolidationService,
                    writeGatePolicy
            );

            MemorySyncBatch batch = new MemorySyncBatch(
                    "evt-gate",
                    List.of(),
                    List.of(new SemanticMemoryEntry("ok", List.of(0.1, 0.2), Instant.now())),
                    List.of()
            );

            MemoryApplyResult result = service.applyUpdates("gate-user", batch);
            assertEquals(0, result.acceptedCount());
            assertEquals(1, result.skippedCount());
            assertTrue(semanticMemoryService.search("gate-user", 10).isEmpty());
        } finally {
            restoreProperty("mindos.memory.write-gate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.min-length", oldMinLength);
        }
    }

    @Test
    void shouldHonorBucketSpecificWriteGateThresholdWhenRecordingSemantic() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.enabled");
        String oldMinLength = System.getProperty("mindos.memory.write-gate.min-length");
        String oldTaskMinLength = System.getProperty("mindos.memory.write-gate.min-length.task");
        try {
            System.setProperty("mindos.memory.write-gate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.min-length", "10");
            System.setProperty("mindos.memory.write-gate.min-length.task", "4");

            MemoryConsolidationService consolidationService = new MemoryConsolidationService();
            CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
            EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
            SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
            ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
            SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
            MemorySyncService service = new MemorySyncService(
                    repository,
                    episodicMemoryService,
                    semanticMemoryService,
                    proceduralMemoryService,
                    consolidationService,
                    writeGatePolicy
            );

            long allowedCursor = service.recordSemantic(
                    "bucket-user",
                    new SemanticMemoryEntry("plan", List.of(0.1, 0.2), Instant.now()),
                    "task"
            );
            long deniedCursor = service.recordSemantic(
                    "bucket-user",
                    new SemanticMemoryEntry("plan", List.of(0.1, 0.2), Instant.now()),
                    "profile"
            );

            assertTrue(allowedCursor > 0);
            assertEquals(0L, deniedCursor);
        } finally {
            restoreProperty("mindos.memory.write-gate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.min-length", oldMinLength);
            restoreProperty("mindos.memory.write-gate.min-length.task", oldTaskMinLength);
        }
    }

    @Test
    void shouldNotDuplicateLocalMemoryWhenApplyingSameEventTwice() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
        MemorySyncService service = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService,
                writeGatePolicy
        );

        MemorySyncBatch batch = new MemorySyncBatch(
                "evt-repeat",
                List.of(new ConversationTurn("user", "hello", Instant.now())),
                List.of(new SemanticMemoryEntry("topic", List.of(1.0, 0.2), Instant.now())),
                List.of(new ProceduralMemoryEntry("code.generate", "build endpoint", true, Instant.now()))
        );

        MemoryApplyResult first = service.applyUpdates("sync-user", batch);
        MemoryApplyResult second = service.applyUpdates("sync-user", batch);

        assertEquals(3, first.acceptedCount());
        assertEquals(0, first.skippedCount());
        assertEquals(0, second.acceptedCount());
        assertEquals(3, second.skippedCount());

        assertEquals(1, episodicMemoryService.getConversation("sync-user").size());
        assertEquals(1, semanticMemoryService.search("sync-user", 10).size());
        assertEquals(1, proceduralMemoryService.getHistory("sync-user").size());
    }

    @Test
    void shouldNormalizeAndDeduplicateSemanticMemoryWithinBatch() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
        MemorySyncService service = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService,
                writeGatePolicy
        );

        MemorySyncBatch batch = new MemorySyncBatch(
                "evt-normalize",
                List.of(),
                List.of(
                        new SemanticMemoryEntry("  Spring   Boot  ", List.of(1.1234567, 2.9876543, 3.1111111, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), Instant.now()),
                        new SemanticMemoryEntry("Spring Boot", List.of(), Instant.now())
                ),
                List.of()
        );

        MemoryApplyResult result = service.applyUpdates("normalize-user", batch);

        List<SemanticMemoryEntry> entries = semanticMemoryService.search("normalize-user", "spring", 10);
        assertEquals(1, result.acceptedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(1, entries.size());
        assertEquals("Spring Boot", entries.get(0).text());
        assertEquals(8, entries.get(0).embedding().size());
    }

    @Test
    void shouldSkipApproximateDuplicateSemanticEntries() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
        MemorySyncService service = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService,
                writeGatePolicy
        );

        MemorySyncBatch batch = new MemorySyncBatch(
                "evt-approx-dedup",
                List.of(),
                List.of(
                        new SemanticMemoryEntry("请在2026-04-01前完成数学作业并复盘记录", List.of(0.11, 0.22, 0.33, 0.44), Instant.now()),
                        new SemanticMemoryEntry("请在2026-04-01前完成数学作业并复盘记录一下", List.of(0.11, 0.22, 0.33, 0.44), Instant.now())
                ),
                List.of()
        );

        MemoryApplyResult result = service.applyUpdates("approx-user", batch);
        List<SemanticMemoryEntry> entries = semanticMemoryService.search("approx-user", "数学作业", 10);

        assertEquals(1, result.acceptedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(1, entries.size());
    }

    @Test
    void shouldMeetBasicSyncPerformanceBaseline() {
        String oldMaxEvents = System.getProperty("mindos.memory.inmemory.max-events-per-user");
        int baselineMillis = parseNonNegativeIntProperty("mindos.memory.sync.perf-baseline-ms", 4000);
        int retryCount = parseNonNegativeIntProperty("mindos.memory.sync.perf-retries", 1);
        try {
            System.setProperty("mindos.memory.inmemory.max-events-per-user", "10000");

            int totalAttempts = retryCount + 1;
            long elapsedMillis = -1L;
            for (int attempt = 1; attempt <= totalAttempts; attempt++) {
                elapsedMillis = runSyncPerformanceScenario(1_500);
                if (elapsedMillis <= baselineMillis) {
                    return;
                }
            }

            assertTrue(elapsedMillis <= baselineMillis,
                    "sync performance baseline exceeded after " + totalAttempts + " attempts"
                            + " (retries=" + retryCount + "): last=" + elapsedMillis + "ms, baseline="
                            + baselineMillis + "ms");
        } finally {
            restoreProperty("mindos.memory.inmemory.max-events-per-user", oldMaxEvents);
        }
    }

    private int parseNonNegativeIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 0 ? defaultValue : parsed;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private long runSyncPerformanceScenario(int batchCount) {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
        MemorySyncService service = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService,
                writeGatePolicy
        );

        long startNanos = System.nanoTime();
        for (int i = 0; i < batchCount; i++) {
            MemorySyncBatch batch = new MemorySyncBatch(
                    "evt-perf-" + i,
                    List.of(new ConversationTurn("user", "msg-" + i, Instant.now())),
                    List.of(new SemanticMemoryEntry("semantic-entry-" + i, List.of(0.1 + i, 0.2 + i, 0.3 + i), Instant.now())),
                    List.of(new ProceduralMemoryEntry("skill.demo", "input-" + i, true, Instant.now()))
            );
            service.applyUpdates("perf-user", batch);
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertEquals(batchCount, repository.fetchSince("perf-user", 0L, 10_000).episodic().size());
        int semanticCount = repository.fetchSince("perf-user", 0L, 10_000).semantic().size();
        assertTrue(semanticCount > 0 && semanticCount <= batchCount);
        assertEquals(batchCount, repository.fetchSince("perf-user", 0L, 10_000).procedural().size());
        return elapsedMillis;
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}


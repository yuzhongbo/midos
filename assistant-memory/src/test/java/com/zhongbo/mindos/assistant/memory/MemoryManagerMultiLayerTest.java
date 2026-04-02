package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerMultiLayerTest {

    @Test
    void shouldExposeAdditiveRouterBackedApis() throws Exception {
        Path baseDir = Files.createTempDirectory("mindos-multilayer-test-");

        MemoryManager memoryManager = createMemoryManager(new FileCentralMemoryRepository(baseDir, new ObjectMapper()));
        memoryManager.saveMemoryRecord(new MemoryRecord(
                null,
                "multi-user",
                "current sprint context",
                MemoryLayer.WORKING,
                null,
                Map.of("sessionId", "session-1", "topic", "sprint"),
                0.88,
                null,
                null
        ));
        memoryManager.saveMemoryRecord(new MemoryRecord(
                null,
                "multi-user",
                "",
                MemoryLayer.FACT,
                null,
                Map.of("subject", "bob", "predicate", "owns", "object", "release-plan"),
                1.0,
                null,
                null
        ));

        List<MemoryRecord> working = memoryManager.queryMemory(MemoryQuery.builder()
                .userId("multi-user")
                .layer(MemoryLayer.WORKING)
                .sessionId("session-1")
                .build());
        List<MemoryRecord> facts = memoryManager.queryMemory(MemoryQuery.builder()
                .userId("multi-user")
                .layer(MemoryLayer.FACT)
                .metadata(Map.of("subject", "bob"))
                .build());

        assertEquals(1, working.size());
        assertEquals("current sprint context", working.get(0).content());
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).content().contains("bob owns release-plan"));
    }

    private MemoryManager createMemoryManager(CentralMemoryRepository repository) {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
        MemorySyncService syncService = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService,
                writeGatePolicy
        );
        MemoryCompressionPlanningService compressionPlanningService = new MemoryCompressionPlanningService(consolidationService);
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2, true);
        LongTaskService longTaskService = new LongTaskService();
        return new MemoryManager(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                syncService,
                consolidationService,
                writeGatePolicy,
                compressionPlanningService,
                preferenceProfileService,
                longTaskService
        );
    }
}

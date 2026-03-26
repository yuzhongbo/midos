package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @Test
    void shouldNotSyncSemanticEntryRejectedBySecondaryDuplicateGate() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.semantic-duplicate.enabled");
        String oldThreshold = System.getProperty("mindos.memory.write-gate.semantic-duplicate.threshold");
        try {
            System.setProperty("mindos.memory.write-gate.semantic-duplicate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.semantic-duplicate.threshold", "0.70");

            MemoryManager memoryManager = createMemoryManager();
            memoryManager.storeKnowledge(
                    "manager-user",
                    "project alpha finalize api integration and regression test",
                    List.of(0.11, 0.22),
                    "task"
            );
            memoryManager.storeKnowledge(
                    "manager-user",
                    "project alpha finalize api integration regression test",
                    List.of(0.12, 0.21),
                    "task"
            );

            List<SemanticMemoryEntry> localEntries = memoryManager.searchKnowledge("manager-user", "project alpha", 10, "task");
            MemorySyncSnapshot snapshot = memoryManager.fetchIncrementalUpdates("manager-user", 0L, 10);

            assertEquals(1, localEntries.size());
            assertEquals(1, snapshot.semantic().size());
        } finally {
            restoreProperty("mindos.memory.write-gate.semantic-duplicate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.semantic-duplicate.threshold", oldThreshold);
        }
    }

    @Test
    void shouldRollupOldConversationTurnsIntoSemanticMemoryWhileKeepingRecentTurnsHot() {
        String oldEnabled = System.getProperty("mindos.memory.conversation-rollup.enabled");
        String oldThreshold = System.getProperty("mindos.memory.conversation-rollup.threshold-turns");
        String oldKeepRecent = System.getProperty("mindos.memory.conversation-rollup.keep-recent-turns");
        String oldMinTurns = System.getProperty("mindos.memory.conversation-rollup.min-turns");
        try {
            System.setProperty("mindos.memory.conversation-rollup.enabled", "true");
            System.setProperty("mindos.memory.conversation-rollup.threshold-turns", "6");
            System.setProperty("mindos.memory.conversation-rollup.keep-recent-turns", "3");
            System.setProperty("mindos.memory.conversation-rollup.min-turns", "3");

            MemoryManager memoryManager = createMemoryManager();
            for (int i = 0; i < 4; i++) {
                memoryManager.storeUserConversation("rollup-user", "用户消息-" + i + " 先梳理任务和风险");
                memoryManager.storeAssistantConversation("rollup-user", "助手回复-" + i + " 已记录截止时间和负责人");
            }

            List<ConversationTurn> hotConversation = memoryManager.getRecentConversation("rollup-user", 10);
            List<ConversationTurn> fullConversation = memoryManager.getConversation("rollup-user");
            List<SemanticMemoryEntry> rollups = memoryManager.searchKnowledge("rollup-user", "截止时间 负责人", 10, "conversation-rollup");

            assertEquals(3, hotConversation.size());
            assertEquals(8, fullConversation.size());
            assertEquals("assistant", hotConversation.get(0).role());
            assertTrue(rollups.stream().anyMatch(entry -> entry.text().contains("会话摘要")));
        } finally {
            restoreProperty("mindos.memory.conversation-rollup.enabled", oldEnabled);
            restoreProperty("mindos.memory.conversation-rollup.threshold-turns", oldThreshold);
            restoreProperty("mindos.memory.conversation-rollup.keep-recent-turns", oldKeepRecent);
            restoreProperty("mindos.memory.conversation-rollup.min-turns", oldMinTurns);
        }
    }

    private MemoryManager createMemoryManager() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
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

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}


package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryManager {
    private static final String PROP_WRITE_GATE_ENABLED = "mindos.memory.write-gate.enabled";
    private static final String PROP_WRITE_GATE_MIN_LENGTH = "mindos.memory.write-gate.min-length";

    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final MemorySyncService memorySyncService;
    private final MemoryConsolidationService memoryConsolidationService;
    private final MemoryCompressionPlanningService memoryCompressionPlanningService;

    public MemoryManager(EpisodicMemoryService episodicMemoryService,
                         SemanticMemoryService semanticMemoryService,
                         ProceduralMemoryService proceduralMemoryService,
                         MemorySyncService memorySyncService,
                         MemoryConsolidationService memoryConsolidationService,
                         MemoryCompressionPlanningService memoryCompressionPlanningService) {
        this.episodicMemoryService = episodicMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.proceduralMemoryService = proceduralMemoryService;
        this.memorySyncService = memorySyncService;
        this.memoryConsolidationService = memoryConsolidationService;
        this.memoryCompressionPlanningService = memoryCompressionPlanningService;
    }

    public void storeUserConversation(String userId, String message) {
        ConversationTurn turn = memoryConsolidationService.consolidateConversationTurn(ConversationTurn.user(message));
        episodicMemoryService.appendTurn(userId, turn);
        memorySyncService.recordEpisodic(userId, turn);
    }

    public void storeAssistantConversation(String userId, String message) {
        ConversationTurn turn = memoryConsolidationService.consolidateConversationTurn(ConversationTurn.assistant(message));
        episodicMemoryService.appendTurn(userId, turn);
        memorySyncService.recordEpisodic(userId, turn);
    }

    public List<ConversationTurn> getConversation(String userId) {
        return episodicMemoryService.getConversation(userId);
    }

    public List<ConversationTurn> getRecentConversation(String userId, int limit) {
        return episodicMemoryService.getRecentConversation(userId, limit);
    }

    public void storeKnowledge(String userId, String text, List<Double> embedding) {
        storeKnowledge(userId, text, embedding, null);
    }

    public void storeKnowledge(String userId, String text, List<Double> embedding, String bucket) {
        SemanticMemoryEntry entry = memoryConsolidationService.consolidateSemanticEntry(SemanticMemoryEntry.of(text, embedding));
        if (entry == null || entry.text().isBlank()) {
            return;
        }
        if (!shouldStoreSemanticMemory(entry.text())) {
            return;
        }
        memorySyncService.recordSemantic(userId, entry, bucket);
        semanticMemoryService.addEntry(userId, entry, bucket);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, int limit) {
        return semanticMemoryService.search(userId, limit);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit) {
        return semanticMemoryService.search(userId, query, limit);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit, String preferredBucket) {
        return semanticMemoryService.search(userId, query, limit, preferredBucket);
    }

    public void logSkillUsage(String userId, String skillName, String input, boolean success) {
        ProceduralMemoryEntry entry = memoryConsolidationService.consolidateProceduralEntry(
                ProceduralMemoryEntry.of(skillName, input, success)
        );
        if (entry == null || entry.skillName().isBlank() || entry.input().isBlank()) {
            return;
        }
        proceduralMemoryService.addEntry(userId, entry);
        memorySyncService.recordProcedural(userId, entry);
    }

    public List<ProceduralMemoryEntry> getSkillUsageHistory(String userId) {
        return proceduralMemoryService.getHistory(userId);
    }

    public List<SkillUsageStats> getSkillUsageStats(String userId) {
        return proceduralMemoryService.getSkillUsageStats(userId);
    }

    public MemorySyncSnapshot fetchIncrementalUpdates(String userId, long sinceCursorExclusive, int limit) {
        return memorySyncService.fetchUpdates(userId, sinceCursorExclusive, limit);
    }

    public MemoryApplyResult applyIncrementalUpdates(String userId, MemorySyncBatch batch) {
        return memorySyncService.applyUpdates(userId, batch);
    }

    public MemoryStyleProfile updateMemoryStyleProfile(
            String userId,
            MemoryStyleProfile styleProfile) {
        return memoryCompressionPlanningService.updateStyleProfile(userId, styleProfile);
    }

    public MemoryStyleProfile updateMemoryStyleProfile(
            String userId,
            MemoryStyleProfile styleProfile,
            boolean autoTune,
            String sampleText) {
        return memoryCompressionPlanningService.updateStyleProfile(userId, styleProfile, autoTune, sampleText);
    }

    public MemoryStyleProfile getMemoryStyleProfile(String userId) {
        return memoryCompressionPlanningService.getStyleProfile(userId);
    }

    public MemoryCompressionPlan buildMemoryCompressionPlan(
            String userId,
            String sourceText,
            MemoryStyleProfile styleOverride) {
        return memoryCompressionPlanningService.buildPlan(userId, sourceText, styleOverride);
    }

    public MemoryCompressionPlan buildMemoryCompressionPlan(
            String userId,
            String sourceText,
            MemoryStyleProfile styleOverride,
            String focus) {
        return memoryCompressionPlanningService.buildPlan(userId, sourceText, styleOverride, focus);
    }

    private boolean shouldStoreSemanticMemory(String text) {
        if (!Boolean.parseBoolean(System.getProperty(PROP_WRITE_GATE_ENABLED, "false"))) {
            return true;
        }
        String normalized = memoryConsolidationService.normalizeText(text);
        if (normalized.isBlank()) {
            return false;
        }
        int minLength = parsePositiveInt(System.getProperty(PROP_WRITE_GATE_MIN_LENGTH, "10"), 10);
        if (memoryConsolidationService.containsKeySignal(normalized)) {
            return true;
        }
        return normalized.length() >= minLength;
    }

    private int parsePositiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

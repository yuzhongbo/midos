package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfileExplain;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Instant;

@Service
public class MemoryManager {
    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final MemorySyncService memorySyncService;
    private final MemoryConsolidationService memoryConsolidationService;
    private final SemanticWriteGatePolicy semanticWriteGatePolicy;
    private final MemoryCompressionPlanningService memoryCompressionPlanningService;
    private final PreferenceProfileService preferenceProfileService;
    private final LongTaskService longTaskService;

    public MemoryManager(EpisodicMemoryService episodicMemoryService,
                         SemanticMemoryService semanticMemoryService,
                         ProceduralMemoryService proceduralMemoryService,
                         MemorySyncService memorySyncService,
                         MemoryConsolidationService memoryConsolidationService,
                         SemanticWriteGatePolicy semanticWriteGatePolicy,
                         MemoryCompressionPlanningService memoryCompressionPlanningService,
                         PreferenceProfileService preferenceProfileService,
                         LongTaskService longTaskService) {
        this.episodicMemoryService = episodicMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.proceduralMemoryService = proceduralMemoryService;
        this.memorySyncService = memorySyncService;
        this.memoryConsolidationService = memoryConsolidationService;
        this.semanticWriteGatePolicy = semanticWriteGatePolicy;
        this.memoryCompressionPlanningService = memoryCompressionPlanningService;
        this.preferenceProfileService = preferenceProfileService;
        this.longTaskService = longTaskService;
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
        if (!semanticWriteGatePolicy.shouldStore(entry.text(), bucket)) {
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

    public PreferenceProfile getPreferenceProfile(String userId) {
        return preferenceProfileService.getProfile(userId);
    }

    public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
        return preferenceProfileService.updateProfile(userId, profile);
    }

    public PreferenceProfileExplain getPreferenceProfileExplain(String userId) {
        return preferenceProfileService.getProfileExplain(userId);
    }

    public LongTask createLongTask(String userId,
                                   String title,
                                   String objective,
                                   List<String> steps,
                                   Instant dueAt,
                                   Instant nextCheckAt) {
        return longTaskService.createTask(userId, title, objective, steps, dueAt, nextCheckAt);
    }

    public List<LongTask> listLongTasks(String userId, String statusFilter) {
        return longTaskService.listTasks(userId, statusFilter);
    }

    public LongTask getLongTask(String userId, String taskId) {
        return longTaskService.getTask(userId, taskId);
    }

    public List<LongTask> claimReadyLongTasks(String userId, String workerId, int limit, long leaseSeconds) {
        return longTaskService.claimReadyTasks(userId, workerId, limit, leaseSeconds);
    }

    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String completedStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompleted) {
        return longTaskService.updateProgress(userId, taskId, workerId, completedStep, note, blockedReason, nextCheckAt, markCompleted);
    }

    public LongTask updateLongTaskStatus(String userId,
                                         String taskId,
                                         LongTaskStatus status,
                                         String note,
                                         Instant nextCheckAt) {
        return longTaskService.updateStatus(userId, taskId, status, note, nextCheckAt);
    }

    public List<String> listLongTaskUsers() {
        return longTaskService.listUserIds();
    }

    public LongTaskService.AutoAdvanceResult autoAdvanceLongTasks(String userId,
                                                                  String workerId,
                                                                  int limit,
                                                                  long leaseSeconds,
                                                                  long nextCheckDelaySeconds) {
        return longTaskService.autoAdvanceReadyTasks(userId, workerId, limit, leaseSeconds, nextCheckDelaySeconds);
    }

}

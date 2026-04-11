package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfileExplain;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryFacade {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("([A-Za-z]{2,}[-_][A-Za-z0-9_-]+|stu-[A-Za-z0-9_-]+)");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("(-?\\d+)");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private final GraphMemory graphMemory;
    private final VectorMemory vectorMemory;
    private final MemoryManager memoryManager;
    private final int vectorTopK;

    public MemoryFacade(GraphMemory graphMemory, VectorMemory vectorMemory) {
        this(graphMemory, vectorMemory, null, 5);
    }

    public MemoryFacade(MemoryManager memoryManager) {
        this(null, null, memoryManager, 5);
    }

    @Autowired
    public MemoryFacade(GraphMemory graphMemory,
                        VectorMemory vectorMemory,
                        ObjectProvider<MemoryManager> memoryManagerProvider,
                        @Value("${mindos.memory.hybrid.vector-top-k:5}") int vectorTopK) {
        this(graphMemory, vectorMemory, memoryManagerProvider == null ? null : memoryManagerProvider.getIfAvailable(), vectorTopK);
    }

    private MemoryFacade(GraphMemory graphMemory,
                         VectorMemory vectorMemory,
                         MemoryManager memoryManager,
                         int vectorTopK) {
        this.graphMemory = graphMemory;
        this.vectorMemory = vectorMemory;
        this.memoryManager = memoryManager;
        this.vectorTopK = Math.max(1, vectorTopK);
    }

    public Optional<Object> infer(String userId, String key) {
        return infer(userId, key, "", Optional::empty);
    }

    public Optional<Object> infer(String userId, String key, String hint) {
        return infer(userId, key, hint, Optional::empty);
    }

    public Optional<Object> infer(String userId,
                                  String key,
                                  String hint,
                                  Supplier<Optional<Object>> defaultValueSupplier) {
        String normalizedKey = normalize(key);
        if (normalizedKey.isBlank()) {
            return safeDefault(defaultValueSupplier);
        }

        if (graphMemory != null) {
            Optional<Object> graphValue = graphMemory.infer(userId, key, hint);
            if (graphValue.isPresent() && !isBlank(graphValue.get())) {
                return graphValue;
            }
        }

        if (vectorMemory != null) {
            String query = firstNonBlank(hint, key);
            List<VectorSearchResult> results = vectorMemory.search(userId, query, vectorTopK);
            for (VectorSearchResult result : results) {
                Optional<Object> vectorValue = inferFromVectorHit(key, hint, result);
                if (vectorValue.isPresent() && !isBlank(vectorValue.get())) {
                    return vectorValue;
                }
            }
        }

        return safeDefault(defaultValueSupplier);
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId) {
        if (graphMemory == null) {
            return List.of();
        }
        return graphMemory.queryRelated(userId, nodeId);
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId, String relation) {
        if (graphMemory == null) {
            return List.of();
        }
        return graphMemory.queryRelated(userId, nodeId, relation);
    }

    public void persistPending() {
        requireMemoryManager().persistPending();
    }

    public void storeUserConversation(String userId, String message) {
        requireMemoryManager().storeUserConversation(userId, message);
    }

    public void storeAssistantConversation(String userId, String message) {
        requireMemoryManager().storeAssistantConversation(userId, message);
    }

    public List<ConversationTurn> getConversation(String userId) {
        return requireMemoryManager().getConversation(userId);
    }

    public List<ConversationTurn> getRecentConversation(String userId, int limit) {
        return requireMemoryManager().getRecentConversation(userId, limit);
    }

    public void storeKnowledge(String userId, String text, List<Double> embedding) {
        requireMemoryManager().storeKnowledge(userId, text, embedding);
    }

    public void storeKnowledge(String userId, String text, List<Double> embedding, String bucket) {
        requireMemoryManager().storeKnowledge(userId, text, embedding, bucket);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, int limit) {
        return requireMemoryManager().searchKnowledge(userId, limit);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit) {
        return requireMemoryManager().searchKnowledge(userId, query, limit);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit, String preferredBucket) {
        return requireMemoryManager().searchKnowledge(userId, query, limit, preferredBucket);
    }

    public void logSkillUsage(String userId, String skillName, String input, boolean success) {
        requireMemoryManager().logSkillUsage(userId, skillName, input, success);
    }

    public List<ProceduralMemoryEntry> getSkillUsageHistory(String userId) {
        return requireMemoryManager().getSkillUsageHistory(userId);
    }

    public List<SkillUsageStats> getSkillUsageStats(String userId) {
        return requireMemoryManager().getSkillUsageStats(userId);
    }

    public PromptMemoryContextDto buildPromptMemoryContext(String userId,
                                                           String userInput,
                                                           int maxChars,
                                                           Map<String, Object> profileContext) {
        return requireMemoryManager().buildPromptMemoryContext(userId, userInput, maxChars, profileContext);
    }

    public MemorySyncSnapshot fetchIncrementalUpdates(String userId, long sinceCursorExclusive, int limit) {
        return requireMemoryManager().fetchIncrementalUpdates(userId, sinceCursorExclusive, limit);
    }

    public MemoryApplyResult applyIncrementalUpdates(String userId, MemorySyncBatch batch) {
        return requireMemoryManager().applyIncrementalUpdates(userId, batch);
    }

    public MemoryStyleProfile updateMemoryStyleProfile(String userId,
                                                       MemoryStyleProfile profile) {
        return requireMemoryManager().updateMemoryStyleProfile(userId, profile);
    }

    public MemoryStyleProfile updateMemoryStyleProfile(String userId,
                                                       MemoryStyleProfile profile,
                                                       boolean autoTune,
                                                       String sampleText) {
        return requireMemoryManager().updateMemoryStyleProfile(userId, profile, autoTune, sampleText);
    }

    public MemoryStyleProfile getMemoryStyleProfile(String userId) {
        return requireMemoryManager().getMemoryStyleProfile(userId);
    }

    public MemoryCompressionPlan buildMemoryCompressionPlan(String userId,
                                                            String sourceText,
                                                            MemoryStyleProfile styleOverride) {
        return requireMemoryManager().buildMemoryCompressionPlan(userId, sourceText, styleOverride);
    }

    public MemoryCompressionPlan buildMemoryCompressionPlan(String userId,
                                                            String sourceText,
                                                            MemoryStyleProfile styleOverride,
                                                            String focus) {
        return requireMemoryManager().buildMemoryCompressionPlan(userId, sourceText, styleOverride, focus);
    }

    public PreferenceProfile getPreferenceProfile(String userId) {
        return requireMemoryManager().getPreferenceProfile(userId);
    }

    public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
        return requireMemoryManager().updatePreferenceProfile(userId, profile);
    }

    public PreferenceProfileExplain getPreferenceProfileExplain(String userId) {
        return requireMemoryManager().getPreferenceProfileExplain(userId);
    }

    public LongTask createLongTask(String userId,
                                   String title,
                                   String objective,
                                   List<String> steps,
                                   Instant dueAt,
                                   Instant nextCheckAt) {
        return requireMemoryManager().createLongTask(userId, title, objective, steps, dueAt, nextCheckAt);
    }

    public List<LongTask> listLongTasks(String userId, String statusFilter) {
        return requireMemoryManager().listLongTasks(userId, statusFilter);
    }

    public LongTask getLongTask(String userId, String taskId) {
        return requireMemoryManager().getLongTask(userId, taskId);
    }

    public List<LongTask> claimReadyLongTasks(String userId, String workerId, int limit, long leaseSeconds) {
        return requireMemoryManager().claimReadyLongTasks(userId, workerId, limit, leaseSeconds);
    }

    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String completedStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompleted) {
        return requireMemoryManager().updateLongTaskProgress(
                userId,
                taskId,
                workerId,
                completedStep,
                note,
                blockedReason,
                nextCheckAt,
                markCompleted
        );
    }

    public LongTask updateLongTaskStatus(String userId,
                                         String taskId,
                                         LongTaskStatus status,
                                         String note,
                                         Instant nextCheckAt) {
        return requireMemoryManager().updateLongTaskStatus(userId, taskId, status, note, nextCheckAt);
    }

    public List<String> listLongTaskUsers() {
        return requireMemoryManager().listLongTaskUsers();
    }

    public LongTaskService.AutoAdvanceResult autoAdvanceLongTasks(String userId,
                                                                  String workerId,
                                                                  int limit,
                                                                  long leaseSeconds,
                                                                  long nextCheckDelaySeconds) {
        return requireMemoryManager().autoAdvanceLongTasks(userId, workerId, limit, leaseSeconds, nextCheckDelaySeconds);
    }

    private Optional<Object> inferFromVectorHit(String key, String hint, VectorSearchResult result) {
        if (result == null || result.record() == null) {
            return Optional.empty();
        }
        Map<String, Object> metadata = result.record().metadata() == null ? Map.of() : result.record().metadata();
        Object direct = metadata.get(key);
        if (!isBlank(direct)) {
            return Optional.of(direct);
        }
        String normalizedKey = normalize(key);
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (normalize(entry.getKey()).equals(normalizedKey) && !isBlank(entry.getValue())) {
                return Optional.of(entry.getValue());
            }
        }
        Object fromContent = inferFromText(key, firstNonBlank(hint, result.record().content()));
        return isBlank(fromContent) ? Optional.empty() : Optional.of(fromContent);
    }

    private Object inferFromText(String key, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalizedKey = normalize(key);
        if (normalizedKey.endsWith("id")) {
            Matcher matcher = IDENTIFIER_PATTERN.matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        }
        if (normalizedKey.contains("week") || normalizedKey.contains("hour") || normalizedKey.contains("count") || normalizedKey.contains("num")) {
            Matcher matcher = INTEGER_PATTERN.matcher(text);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        }
        if (normalizedKey.contains("rate") || normalizedKey.contains("score") || normalizedKey.contains("confidence")) {
            Matcher matcher = DOUBLE_PATTERN.matcher(text);
            return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
        }
        return null;
    }

    private Optional<Object> safeDefault(Supplier<Optional<Object>> defaultValueSupplier) {
        if (defaultValueSupplier == null) {
            return Optional.empty();
        }
        Optional<Object> value = defaultValueSupplier.get();
        return value == null ? Optional.empty() : value.filter(item -> !isBlank(item));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private MemoryManager requireMemoryManager() {
        if (memoryManager == null) {
            throw new IllegalStateException("MemoryManager is unavailable for runtime memory operations");
        }
        return memoryManager;
    }
}

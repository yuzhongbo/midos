package com.zhongbo.mindos.assistant.dispatcher.memory;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.Procedure;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryView;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Component
public class DispatcherMemoryFacade {

    private static final Pattern ROUTING_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}.#_-]+");

    private final MemoryFacade memoryFacade;
    private final MemoryGateway memoryGateway;
    private final GraphMemoryView graphMemoryView;
    private final GraphMemoryGateway graphMemoryGateway;
    private final ProceduralMemory proceduralMemory;
    private final int conversationHistoryLimit;
    private final int knowledgeLimit;
    private final int habitSkillStatsLimit;
    private final int memoryContextKeepRecentTurns;
    private final int memoryContextHistorySummaryMinTurns;

    public record MemoryCompressionStats(int rawChars, int finalChars, boolean compressed, int summarizedTurns) {
    }

    public DispatcherMemoryFacade(MemoryFacade memoryFacade) {
        this(memoryFacade, (MemoryGateway) null, (GraphMemoryView) null, (GraphMemoryGateway) null, (ProceduralMemory) null, 6, 3, 3, 2, 4);
    }

    public DispatcherMemoryFacade(MemoryFacade memoryFacade,
                                  int conversationHistoryLimit,
                                  int knowledgeLimit,
                                  int habitSkillStatsLimit,
                                  int memoryContextKeepRecentTurns,
                                  int memoryContextHistorySummaryMinTurns) {
        this(
                memoryFacade,
                (MemoryGateway) null,
                (GraphMemoryView) null,
                (GraphMemoryGateway) null,
                (ProceduralMemory) null,
                conversationHistoryLimit,
                knowledgeLimit,
                habitSkillStatsLimit,
                memoryContextKeepRecentTurns,
                memoryContextHistorySummaryMinTurns
        );
    }

    public DispatcherMemoryFacade(MemoryFacade memoryFacade,
                                  MemoryGateway memoryGateway,
                                  GraphMemoryView graphMemoryView,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory) {
        this(memoryFacade, memoryGateway, graphMemoryView, graphMemoryGateway, proceduralMemory, 6, 3, 3, 2, 4);
    }

    public DispatcherMemoryFacade(MemoryFacade memoryFacade,
                                  MemoryGateway memoryGateway,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory) {
        this(
                memoryFacade,
                memoryGateway,
                graphMemoryGateway instanceof GraphMemoryView graphView ? graphView : null,
                graphMemoryGateway,
                proceduralMemory,
                6,
                3,
                3,
                2,
                4
        );
    }

    public DispatcherMemoryFacade(MemoryGateway memoryGateway,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory) {
        this(
                null,
                memoryGateway,
                graphMemoryGateway instanceof GraphMemoryView graphView ? graphView : null,
                graphMemoryGateway,
                proceduralMemory,
                6,
                3,
                3,
                2,
                4
        );
    }

    @Autowired
    public DispatcherMemoryFacade(MemoryFacade memoryFacade,
                                  ObjectProvider<MemoryGateway> memoryGatewayProvider,
                                  ObjectProvider<GraphMemoryView> graphMemoryViewProvider,
                                  ObjectProvider<GraphMemoryGateway> graphMemoryGatewayProvider,
                                  ObjectProvider<ProceduralMemory> proceduralMemoryProvider,
                                  @Value("${mindos.dispatcher.memory-context.history-limit:6}") int conversationHistoryLimit,
                                  @Value("${mindos.dispatcher.memory-context.knowledge-limit:3}") int knowledgeLimit,
                                  @Value("${mindos.dispatcher.memory-context.habit-stats-limit:3}") int habitSkillStatsLimit,
                                  @Value("${mindos.dispatcher.memory-context.keep-recent-turns:2}") int memoryContextKeepRecentTurns,
                                  @Value("${mindos.dispatcher.memory-context.history-summary-min-turns:4}") int memoryContextHistorySummaryMinTurns) {
        this(
                memoryFacade,
                memoryGatewayProvider == null ? null : memoryGatewayProvider.getIfAvailable(),
                graphMemoryViewProvider == null ? null : graphMemoryViewProvider.getIfAvailable(),
                graphMemoryGatewayProvider == null ? null : graphMemoryGatewayProvider.getIfAvailable(),
                proceduralMemoryProvider == null ? null : proceduralMemoryProvider.getIfAvailable(),
                conversationHistoryLimit,
                knowledgeLimit,
                habitSkillStatsLimit,
                memoryContextKeepRecentTurns,
                memoryContextHistorySummaryMinTurns
        );
    }

    private DispatcherMemoryFacade(MemoryFacade memoryFacade,
                                   MemoryGateway memoryGateway,
                                   GraphMemoryView graphMemoryView,
                                   GraphMemoryGateway graphMemoryGateway,
                                   ProceduralMemory proceduralMemory,
                                   int conversationHistoryLimit,
                                   int knowledgeLimit,
                                   int habitSkillStatsLimit,
                                   int memoryContextKeepRecentTurns,
                                   int memoryContextHistorySummaryMinTurns) {
        this.memoryFacade = memoryFacade;
        this.memoryGateway = memoryGateway;
        this.graphMemoryView = graphMemoryView;
        this.graphMemoryGateway = graphMemoryGateway;
        this.proceduralMemory = proceduralMemory;
        this.conversationHistoryLimit = Math.max(1, conversationHistoryLimit);
        this.knowledgeLimit = Math.max(1, knowledgeLimit);
        this.habitSkillStatsLimit = Math.max(1, habitSkillStatsLimit);
        this.memoryContextKeepRecentTurns = Math.max(1, memoryContextKeepRecentTurns);
        this.memoryContextHistorySummaryMinTurns = Math.max(2, memoryContextHistorySummaryMinTurns);
    }

    public PromptMemoryContextDto buildPromptMemoryContext(String userId,
                                                           String userInput,
                                                           int maxChars,
                                                           Map<String, Object> profileContext) {
        return memoryFacade.buildPromptMemoryContext(userId, userInput, maxChars, profileContext);
    }

    public List<Map<String, Object>> buildChatHistory(String userId) {
        List<ConversationTurn> recentConversation = memoryFacade.getRecentConversation(userId, conversationHistoryLimit);
        if (recentConversation == null || recentConversation.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (ConversationTurn turn : recentConversation) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String role = turn.role() == null || turn.role().isBlank() ? "assistant" : turn.role();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", turn.content());
            if (turn.createdAt() != null) {
                item.put("createdAt", turn.createdAt().toString());
            }
            history.add(item);
        }
        return List.copyOf(history);
    }

    public SkillContext buildSkillContext(String userId,
                                         String routingInput,
                                         String originalInput,
                                         Map<String, Object> resolvedProfileContext,
                                         String memoryContext,
                                         List<Map<String, Object>> chatHistory,
                                         SemanticAnalysisResult semanticAnalysis) {
        Map<String, Object> attributes = new LinkedHashMap<>(resolvedProfileContext == null ? Map.of() : resolvedProfileContext);
        if (originalInput != null && !originalInput.isBlank()) {
            attributes.put("originalInput", originalInput);
        }
        if (memoryContext != null) {
            attributes.put("memoryContext", memoryContext);
        }
        if (chatHistory != null && !chatHistory.isEmpty()) {
            attributes.put("chatHistory", List.copyOf(chatHistory));
        }
        if (semanticAnalysis != null) {
            attributes.putAll(semanticAnalysis.asAttributes());
        }
        return new SkillContext(userId, routingInput, attributes);
    }

    public String buildMemoryContext(String userId,
                                     String userInput,
                                     int memoryContextMaxChars,
                                     Consumer<MemoryCompressionStats> metricsConsumer) {
        List<ConversationTurn> recentConversation = memoryFacade.getRecentConversation(userId, conversationHistoryLimit);
        List<SemanticMemoryEntry> conversationRollups = memoryFacade.searchKnowledge(
                userId,
                userInput,
                1,
                "conversation-rollup"
        );
        List<SemanticMemoryEntry> knowledge = memoryFacade.searchKnowledge(
                userId,
                userInput,
                knowledgeLimit,
                inferMemoryBucket(userInput)
        );
        List<SkillUsageStats> usageStats = memoryFacade.getSkillUsageStats(userId).stream()
                .sorted(Comparator.comparingLong(SkillUsageStats::successCount).reversed())
                .limit(habitSkillStatsLimit)
                .toList();

        int historyBudget = Math.max(160, (int) (memoryContextMaxChars * 0.5));
        int knowledgeBudget = Math.max(120, (int) (memoryContextMaxChars * 0.3));
        int habitsBudget = Math.max(80, memoryContextMaxChars - historyBudget - knowledgeBudget);

        String rawConversationContext = buildRawConversationContext(recentConversation);
        String compressedConversationContext = buildConversationContext(userId, recentConversation, conversationRollups);
        String rawKnowledgeContext = buildKnowledgeContext(knowledge);
        String rawHabitContext = buildHabitContext(usageStats);

        StringBuilder builder = new StringBuilder();
        appendContextSection(builder, "Recent conversation", compressedConversationContext, historyBudget);
        appendContextSection(builder, "Relevant knowledge", rawKnowledgeContext, knowledgeBudget);
        appendContextSection(builder, "User skill habits", rawHabitContext, habitsBudget);
        String finalContext = capText(builder.toString(), memoryContextMaxChars);
        if (metricsConsumer != null) {
            metricsConsumer.accept(new MemoryCompressionStats(
                    rawConversationContext.length(),
                    compressedConversationContext.length(),
                    compressedConversationContext.length() < rawConversationContext.length() || !conversationRollups.isEmpty(),
                    Math.max(0, recentConversation.size() - memoryContextKeepRecentTurns)
            ));
        }
        return finalContext;
    }

    public String buildDecisionMemoryContext(PromptMemoryContextDto promptMemoryContext,
                                             String activeTaskContext,
                                             String userInput,
                                             boolean realtimeIntentInput,
                                             boolean realtimeIntentMemoryShrinkEnabled,
                                             int realtimeIntentMemoryShrinkMaxChars,
                                             int maxChars) {
        int safeMaxChars = Math.max(160, maxChars);
        String activeTask = compactActiveTaskContext(activeTaskContext, Math.max(80, safeMaxChars / 3));
        if (shouldApplyRealtimeMemoryShrink(realtimeIntentInput, realtimeIntentMemoryShrinkEnabled)) {
            if (activeTask.isBlank()) {
                return "";
            }
            StringBuilder realtime = new StringBuilder();
            appendDecisionSection(realtime, "Active task", activeTask, Math.max(80, safeMaxChars));
            return capText(realtime.toString().trim(),
                    Math.min(safeMaxChars, Math.max(120, realtimeIntentMemoryShrinkMaxChars)));
        }

        String semantic = compactSemanticDecisionContext(
                promptMemoryContext == null ? "" : promptMemoryContext.semanticContext(),
                Math.max(140, safeMaxChars / 2)
        );
        String recent = shouldIncludeRecentDecisionContext(userInput, activeTask)
                ? compactRecentDecisionContext(
                promptMemoryContext == null ? "" : promptMemoryContext.recentConversation(),
                Math.max(100, safeMaxChars / 4)
        )
                : "";

        StringBuilder builder = new StringBuilder();
        appendDecisionSection(builder, "Active task", activeTask, Math.max(80, safeMaxChars / 3));
        appendDecisionSection(builder, "Relevant facts", semantic, Math.max(120, safeMaxChars / 2));
        appendDecisionSection(builder, "Recent context", recent, Math.max(80, safeMaxChars / 4));
        return capText(builder.toString().trim(), safeMaxChars);
    }

    public String enrichMemoryContextWithSemanticAnalysis(String memoryContext,
                                                          SemanticAnalysisResult semanticAnalysis,
                                                          double minConfidence,
                                                          int memoryContextMaxChars,
                                                          int semanticSummaryMinChars) {
        if (semanticAnalysis == null || semanticAnalysis.confidence() < minConfidence) {
            return memoryContext;
        }
        String summary = semanticAnalysis.toPromptSummary();
        if (summary.isBlank()) {
            return memoryContext;
        }
        String semanticSection = "Semantic analysis:\n"
                + capText(summary, Math.max(semanticSummaryMinChars, memoryContextMaxChars / 3));
        String baseContext = memoryContext == null ? "" : memoryContext;
        return capText(semanticSection + "\n" + baseContext, memoryContextMaxChars);
    }

    public Map<String, Object> buildFallbackLlmContext(String userId,
                                                       String routingInput,
                                                       String originalInput,
                                                       Map<String, Object> resolvedProfileContext,
                                                       SemanticAnalysisResult semanticAnalysis,
                                                       String memoryContext,
                                                       PromptMemoryContextDto promptMemoryContext,
                                                       List<Map<String, Object>> chatHistory,
                                                       boolean realtimeIntentInput,
                                                       boolean realtimeIntentMemoryShrinkEnabled,
                                                       boolean realtimeIntentMemoryShrinkIncludePersona,
                                                       int realtimeIntentMemoryShrinkMaxChars,
                                                       String routeStage) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        if (userId != null && !userId.isBlank()) {
            llmContext.put("userId", userId);
        }
        if (routingInput != null && !routingInput.isBlank()) {
            llmContext.put("input", routingInput);
        }
        if (originalInput != null && !originalInput.isBlank()) {
            llmContext.put("originalInput", originalInput);
        }
        if (semanticAnalysis != null) {
            llmContext.put("semanticAnalysis", semanticAnalysis.asAttributes());
        } else {
            llmContext.put("semanticAnalysis", Map.of());
        }
        if (routeStage != null && !routeStage.isBlank()) {
            llmContext.put("routeStage", routeStage);
        }
        llmContext.put("profile", resolvedProfileContext == null ? Map.of() : new LinkedHashMap<>(resolvedProfileContext));
        if (chatHistory != null && !chatHistory.isEmpty()) {
            llmContext.put("chatHistory", List.copyOf(chatHistory));
        }
        populateFallbackMemoryContext(
                llmContext,
                memoryContext,
                promptMemoryContext,
                realtimeIntentInput,
                realtimeIntentMemoryShrinkEnabled,
                realtimeIntentMemoryShrinkIncludePersona,
                realtimeIntentMemoryShrinkMaxChars
        );
        return llmContext;
    }

    public void populateFallbackMemoryContext(Map<String, Object> llmContext,
                                              String memoryContext,
                                              PromptMemoryContextDto promptMemoryContext,
                                              boolean realtimeIntentInput,
                                              boolean realtimeIntentMemoryShrinkEnabled,
                                              boolean realtimeIntentMemoryShrinkIncludePersona,
                                              int realtimeIntentMemoryShrinkMaxChars) {
        Objects.requireNonNull(llmContext, "llmContext");
        if (shouldApplyRealtimeMemoryShrink(realtimeIntentInput, realtimeIntentMemoryShrinkEnabled)) {
            llmContext.put("memoryContext", "");
            llmContext.put("memory.recent", "");
            llmContext.put("memory.semantic", "");
            llmContext.put("memory.procedural", "");
            Object persona = realtimeIntentMemoryShrinkIncludePersona && promptMemoryContext != null
                    ? promptMemoryContext.personaSnapshot()
                    : Map.of();
            llmContext.put("memory.persona", persona == null ? Map.of() : persona);
            llmContext.put("memory.shrinkApplied", true);
            return;
        }
        llmContext.put("memoryContext", memoryContext);
        llmContext.put("memory.recent", promptMemoryContext == null ? "" : promptMemoryContext.recentConversation());
        llmContext.put("memory.semantic", promptMemoryContext == null ? "" : promptMemoryContext.semanticContext());
        llmContext.put("memory.procedural", promptMemoryContext == null ? "" : promptMemoryContext.proceduralHints());
        llmContext.put("memory.persona", promptMemoryContext == null ? Map.of() : promptMemoryContext.personaSnapshot());
    }

    public List<ProceduralMemoryEntry> getSkillUsageHistory(String userId) {
        if (memoryFacade == null) {
            return List.of();
        }
        return memoryFacade.getSkillUsageHistory(userId);
    }

    public boolean hasRuntimeMemory() {
        return memoryGateway != null || memoryFacade != null;
    }

    public PreferenceProfile getPreferenceProfile(String userId) {
        if (memoryFacade == null) {
            return PreferenceProfile.empty();
        }
        return memoryFacade.getPreferenceProfile(userId);
    }

    public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
        if (memoryGateway != null) {
            return memoryGateway.updatePreferenceProfile(userId, profile);
        }
        if (memoryFacade == null) {
            return PreferenceProfile.empty();
        }
        return memoryFacade.updatePreferenceProfile(userId, profile);
    }

    public List<SkillUsageStats> getSkillUsageStats(String userId) {
        if (memoryGateway != null) {
            return memoryGateway.skillUsageStats(userId);
        }
        if (memoryFacade == null) {
            return List.of();
        }
        return memoryFacade.getSkillUsageStats(userId);
    }

    public List<ConversationTurn> recentHistory(String userId) {
        if (memoryGateway != null) {
            return memoryGateway.recentHistory(userId);
        }
        if (memoryFacade == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        return memoryFacade.getRecentConversation(userId, conversationHistoryLimit);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit, String bucket) {
        if (memoryFacade == null) {
            return List.of();
        }
        return memoryFacade.searchKnowledge(userId, query, limit, bucket);
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId) {
        if (memoryFacade == null || nodeId == null || nodeId.isBlank()) {
            return List.of();
        }
        return memoryFacade.queryRelated(userId, nodeId);
    }

    public Optional<Object> infer(String userId, String key, String hint) {
        if (memoryFacade == null) {
            return Optional.empty();
        }
        return memoryFacade.infer(userId, key, hint);
    }

    public Optional<ProceduralMemory.ReusableProcedure> matchReusableProcedure(String userId,
                                                                               String userInput,
                                                                               String suggestedTarget,
                                                                               Map<String, Object> effectiveParams) {
        if (proceduralMemory == null) {
            return Optional.empty();
        }
        return proceduralMemory.matchReusableProcedure(
                userId,
                userInput,
                suggestedTarget,
                effectiveParams == null ? Map.of() : effectiveParams
        );
    }

    public List<MemoryNode> searchGraphNodes(String userId, String keyword, int limit) {
        if (graphMemoryView == null) {
            return List.of();
        }
        return graphMemoryView.searchNodes(userId, keyword, limit);
    }

    public Map<String, Double> scoreGraphCandidates(String userId, String userInput, List<String> candidateNames) {
        if (graphMemoryView == null) {
            return Map.of();
        }
        return graphMemoryView.scoreCandidates(userId, userInput, candidateNames);
    }

    public void appendUserConversation(String userId, String message) {
        if (memoryGateway != null) {
            memoryGateway.appendUserConversation(userId, message);
            return;
        }
        if (memoryFacade != null) {
            memoryFacade.storeUserConversation(userId, message);
        }
    }

    public void appendAssistantConversation(String userId, String message) {
        if (memoryGateway != null) {
            memoryGateway.appendAssistantConversation(userId, message == null ? "" : message);
            return;
        }
        if (memoryFacade != null) {
            memoryFacade.storeAssistantConversation(userId, message);
        }
    }

    public void recordSkillUsage(String userId, String skillName, String trigger, boolean success) {
        if (memoryGateway != null) {
            memoryGateway.recordSkillUsage(userId, skillName, trigger, success);
            return;
        }
        if (memoryFacade != null) {
            memoryFacade.logSkillUsage(userId, skillName, trigger, success);
        }
    }

    public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
        if (entry == null) {
            return;
        }
        if (memoryGateway != null) {
            memoryGateway.writeProcedural(userId, entry);
            return;
        }
        if (memoryFacade != null && entry.skillName() != null && !entry.skillName().isBlank()) {
            memoryFacade.logSkillUsage(userId, entry.skillName(), entry.input(), entry.success());
        }
    }

    public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
        if (memoryGateway != null) {
            memoryGateway.writeSemantic(userId, text, embedding == null ? List.of() : embedding, bucket);
            return;
        }
        if (memoryFacade != null) {
            memoryFacade.storeKnowledge(userId, text, embedding, bucket);
        }
    }

    public Procedure recordProcedureSuccess(String userId,
                                            String intent,
                                            String trigger,
                                            TaskGraph graph,
                                            Map<String, Object> contextAttributes) {
        if (proceduralMemory == null) {
            return null;
        }
        return proceduralMemory.recordSuccess(
                userId,
                intent,
                trigger,
                graph,
                contextAttributes == null ? Map.of() : contextAttributes
        );
    }

    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String completedStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompleted) {
        if (memoryGateway != null) {
            return memoryGateway.updateLongTaskProgress(
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
        if (memoryFacade == null) {
            return null;
        }
        return memoryFacade.updateLongTaskProgress(
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

    public boolean hasGraphMemory() {
        return graphMemoryGateway != null;
    }

    public MemoryNode upsertGraphNode(String userId, MemoryNode node) {
        if (graphMemoryGateway == null || node == null) {
            return null;
        }
        return graphMemoryGateway.upsertNode(userId, node);
    }

    public MemoryEdge linkGraph(String userId,
                                String from,
                                String relation,
                                String to,
                                double weight,
                                Map<String, Object> data) {
        if (graphMemoryGateway == null) {
            return null;
        }
        return graphMemoryGateway.link(userId, from, relation, to, weight, data == null ? Map.of() : data);
    }

    public boolean deleteGraphNode(String userId, String nodeId) {
        return graphMemoryGateway != null && graphMemoryGateway.deleteNode(userId, nodeId);
    }

    private boolean shouldApplyRealtimeMemoryShrink(boolean realtimeIntentInput, boolean realtimeIntentMemoryShrinkEnabled) {
        return realtimeIntentInput && realtimeIntentMemoryShrinkEnabled;
    }

    private String buildConversationContext(String userId,
                                            List<ConversationTurn> recentConversation,
                                            List<SemanticMemoryEntry> conversationRollups) {
        if (recentConversation.isEmpty()) {
            return buildConversationRollupPrefix(conversationRollups) + "- none\n";
        }
        int keepRecent = Math.min(memoryContextKeepRecentTurns, recentConversation.size());
        int splitIndex = Math.max(0, recentConversation.size() - keepRecent);
        List<ConversationTurn> olderTurns = recentConversation.size() >= memoryContextHistorySummaryMinTurns
                ? recentConversation.subList(0, splitIndex)
                : List.of();
        List<ConversationTurn> preservedTurns = recentConversation.subList(splitIndex, recentConversation.size());

        StringBuilder builder = new StringBuilder(buildConversationRollupPrefix(conversationRollups));
        String olderSummary = summarizeOlderConversation(userId, olderTurns);
        if (!olderSummary.isBlank()) {
            builder.append("- earlier summary: ").append(olderSummary).append('\n');
        }
        for (ConversationTurn turn : preservedTurns) {
            builder.append("- ").append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return builder.toString();
    }

    private String buildConversationRollupPrefix(List<SemanticMemoryEntry> conversationRollups) {
        if (conversationRollups == null || conversationRollups.isEmpty()) {
            return "";
        }
        return conversationRollups.stream()
                .map(SemanticMemoryEntry::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .map(text -> "- persisted rollup: " + text + '\n')
                .orElse("");
    }

    private String buildRawConversationContext(List<ConversationTurn> recentConversation) {
        if (recentConversation.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : recentConversation) {
            builder.append("- ").append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return builder.toString();
    }

    private String summarizeOlderConversation(String userId, List<ConversationTurn> olderTurns) {
        if (olderTurns == null || olderTurns.isEmpty()) {
            return "";
        }
        String source = olderTurns.stream()
                .map(turn -> turn.role() + ": " + turn.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (source.isBlank()) {
            return "";
        }
        MemoryCompressionPlan plan = memoryFacade.buildMemoryCompressionPlan(
                userId,
                source,
                new MemoryStyleProfile("concise", "direct", "bullet"),
                "review"
        );
        return plan.steps().stream()
                .filter(step -> "BRIEF".equals(step.stage()))
                .map(step -> step.content().replace('\n', ' '))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String buildKnowledgeContext(List<SemanticMemoryEntry> knowledge) {
        if (knowledge.isEmpty()) {
            return "- none\n";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (SemanticMemoryEntry entry : knowledge) {
            if (entry != null && entry.text() != null && !entry.text().isBlank()) {
                unique.add(entry.text());
            }
        }
        if (unique.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (String text : unique) {
            builder.append("- ").append(text).append('\n');
        }
        return builder.toString();
    }

    private String buildHabitContext(List<SkillUsageStats> usageStats) {
        if (usageStats.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (SkillUsageStats stats : usageStats) {
            long total = Math.max(1L, stats.totalCount());
            long successRate = Math.round(stats.successCount() * 100.0 / total);
            builder.append("- ")
                    .append(stats.skillName())
                    .append(" (success=")
                    .append(stats.successCount())
                    .append("/")
                    .append(stats.totalCount())
                    .append(", rate=")
                    .append(successRate)
                    .append("%)\n");
        }
        return builder.toString();
    }

    private void appendContextSection(StringBuilder builder, String title, String content, int budget) {
        builder.append(title).append(":\n");
        builder.append(capText(content == null || content.isBlank() ? "- none\n" : content, budget));
        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
    }

    private void appendDecisionSection(StringBuilder builder, String title, String content, int budget) {
        if (content == null || content.isBlank()) {
            return;
        }
        builder.append(title).append(":\n");
        builder.append(capText(content, budget));
        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
    }

    private String inferMemoryBucket(String userInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return "general";
        }
        if (containsAny(normalized,
                "学习计划", "教学规划", "复习计划", "备考", "课程", "学科", "数学", "英语", "物理", "化学")) {
            return "learning";
        }
        if (containsAny(normalized,
                "情商", "沟通", "同事", "关系", "冲突", "安抚", "eq", "coach")) {
            return "eq";
        }
        if (containsAny(normalized,
                "待办", "todo", "截止", "任务", "清单", "优先级", "计划")) {
            return "task";
        }
        return "general";
    }

    private boolean containsAny(String input, String... keywords) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && input.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String compactActiveTaskContext(String activeTaskContext, int budget) {
        if (activeTaskContext == null || activeTaskContext.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : activeTaskContext.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || "Active task thread:".equalsIgnoreCase(line)) {
                continue;
            }
            lines.add(line);
            if (lines.size() >= 4) {
                break;
            }
        }
        return capText(String.join("\n", lines), budget);
    }

    private String compactSemanticDecisionContext(String semanticContext, int budget) {
        if (semanticContext == null || semanticContext.isBlank()) {
            return "";
        }
        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        for (String rawLine : semanticContext.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || shouldSkipDecisionSemanticLine(line)) {
                continue;
            }
            if (line.startsWith("- [fact]") || line.startsWith("- [working]")) {
                primary.add(line);
                continue;
            }
            if (line.startsWith("- [buffer]")) {
                secondary.add(line);
                continue;
            }
            if (line.startsWith("- [assistant-context]") || line.startsWith("- [summary]")) {
                secondary.add(line);
                continue;
            }
            primary.add(line);
        }
        List<String> selected = new ArrayList<>();
        for (String line : primary) {
            if (selected.size() >= 3) {
                break;
            }
            selected.add(line);
        }
        for (String line : secondary) {
            if (selected.size() >= 3) {
                break;
            }
            selected.add(line);
        }
        return capText(String.join("\n", selected), budget);
    }

    private boolean shouldSkipDecisionSemanticLine(String line) {
        String normalized = normalize(line);
        return normalized.contains("当前事项")
                || normalized.contains("下一步")
                || normalized.contains("截止时间")
                || normalized.contains("状态")
                || normalized.contains("偏好");
    }

    private String compactRecentDecisionContext(String recentConversation, int budget) {
        if (recentConversation == null || recentConversation.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : recentConversation.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            lines.add(line);
        }
        if (lines.size() > 2) {
            lines = new ArrayList<>(lines.subList(lines.size() - 2, lines.size()));
        }
        return capText(String.join("\n", lines), budget);
    }

    private boolean shouldIncludeRecentDecisionContext(String userInput, String activeTaskContext) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return false;
        }
        if (!activeTaskContext.isBlank()) {
            return true;
        }
        if (normalized.length() <= 24) {
            return true;
        }
        return containsAny(normalized,
                "继续", "再来", "刚才", "之前", "按之前", "这个", "那个", "上一个", "上次", "照刚才");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String capText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "\n...[truncated]";
    }
}

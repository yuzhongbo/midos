package com.zhongbo.mindos.assistant.dispatcher.memory;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Component
public class DispatcherMemoryFacade {

    private static final Pattern ROUTING_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}.#_-]+");

    private final MemoryFacade memoryFacade;
    private final int conversationHistoryLimit;
    private final int knowledgeLimit;
    private final int habitSkillStatsLimit;
    private final int memoryContextKeepRecentTurns;
    private final int memoryContextHistorySummaryMinTurns;

    public record MemoryCompressionStats(int rawChars, int finalChars, boolean compressed, int summarizedTurns) {
    }

    public DispatcherMemoryFacade(MemoryFacade memoryFacade) {
        this(memoryFacade, 6, 3, 3, 2, 4);
    }

    @Autowired
    public DispatcherMemoryFacade(MemoryFacade memoryFacade,
                                  @Value("${mindos.dispatcher.memory-context.history-limit:6}") int conversationHistoryLimit,
                                  @Value("${mindos.dispatcher.memory-context.knowledge-limit:3}") int knowledgeLimit,
                                  @Value("${mindos.dispatcher.memory-context.habit-stats-limit:3}") int habitSkillStatsLimit,
                                  @Value("${mindos.dispatcher.memory-context.keep-recent-turns:2}") int memoryContextKeepRecentTurns,
                                  @Value("${mindos.dispatcher.memory-context.history-summary-min-turns:4}") int memoryContextHistorySummaryMinTurns) {
        this.memoryFacade = memoryFacade;
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
        return memoryFacade.getSkillUsageHistory(userId);
    }

    public List<SkillUsageStats> getSkillUsageStats(String userId) {
        return memoryFacade.getSkillUsageStats(userId);
    }

    public List<SemanticMemoryEntry> searchKnowledge(String userId, String query, int limit, String bucket) {
        return memoryFacade.searchKnowledge(userId, query, limit, bucket);
    }

    public void appendUserConversation(String userId, String message) {
        memoryFacade.storeUserConversation(userId, message);
    }

    public void appendAssistantConversation(String userId, String message) {
        memoryFacade.storeAssistantConversation(userId, message);
    }

    public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
        memoryFacade.storeKnowledge(userId, text, embedding, bucket);
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

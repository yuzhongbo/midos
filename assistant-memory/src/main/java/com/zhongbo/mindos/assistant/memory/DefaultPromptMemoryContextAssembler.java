package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import com.zhongbo.mindos.assistant.common.dto.TaskThreadSnapshotDto;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultPromptMemoryContextAssembler implements PromptMemoryContextAssembler {

    private static final String CONVERSATION_ROLLUP_BUCKET = "conversation-rollup";
    private static final String SEMANTIC_SUMMARY_PREFIX = "semantic-summary ";
    private static final String INTENT_SUMMARY_MARKER = "[意图摘要]";
    private static final String ASSISTANT_CONTEXT_MARKER = "[助手上下文]";
    private static final String TASK_FACT_MARKER = "[任务事实]";
    private static final String TASK_STATE_MARKER = "[任务状态]";
    private static final String LEARNING_SIGNAL_MARKER = "[学习信号]";
    private static final String CONVERSATION_SUMMARY_MARKER = "[会话摘要]";
    private static final String REVIEW_FOCUS_MARKER = "[复盘聚焦]";
    private static final int RECENT_TURNS_LIMIT = 6;
    private static final int SEMANTIC_LIMIT = 10;
    private static final int DEBUG_ITEMS_LIMIT = 12;
    private static final int LAYER_PRIORITY_WINDOW = 6;

    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final PreferenceProfileService preferenceProfileService;

    public DefaultPromptMemoryContextAssembler(EpisodicMemoryService episodicMemoryService,
                                               SemanticMemoryService semanticMemoryService,
                                               ProceduralMemoryService proceduralMemoryService,
                                               PreferenceProfileService preferenceProfileService) {
        this.episodicMemoryService = episodicMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.proceduralMemoryService = proceduralMemoryService;
        this.preferenceProfileService = preferenceProfileService;
    }

    @Override
    public PromptMemoryContextDto assemble(String userId, String query, int maxChars, Map<String, Object> profileContext) {
        int safeMaxChars = Math.max(400, maxChars);
        String normalizedQuery = normalize(query);

        List<RetrievedMemoryItemDto> candidates = new ArrayList<>();

        List<ConversationTurn> recentTurns = episodicMemoryService.getRecentConversation(userId, RECENT_TURNS_LIMIT);
        String recentConversation = buildRecentConversation(recentTurns, safeMaxChars * 35 / 100, normalizedQuery, candidates);

        List<RankedSemanticMemory> semanticEntries = semanticMemoryService.searchDetailed(userId, query, SEMANTIC_LIMIT, null);
        String semanticContext = buildSemanticContext(semanticEntries, safeMaxChars * 45 / 100, normalizedQuery, candidates);

        List<ProceduralMemoryEntry> proceduralHistory = proceduralMemoryService.getHistory(userId);
        List<SkillUsageStats> usageStats = proceduralMemoryService.getSkillUsageStats(userId);
        String proceduralHints = isConversationalQuery(normalizedQuery)
                ? ""
                : buildProceduralHints(proceduralHistory, usageStats, safeMaxChars * 20 / 100, normalizedQuery, candidates);

        Map<String, Object> personaSnapshot = buildPersonaSnapshot(userId, profileContext);
        TaskThreadSnapshotDto taskThreadSnapshot = buildTaskThreadSnapshot(semanticEntries);
        Map<String, Object> learnedPreferences = buildLearnedPreferences(taskThreadSnapshot, semanticEntries);

        List<RetrievedMemoryItemDto> debugTopItems = candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievedMemoryItemDto::finalScore).reversed())
                .limit(DEBUG_ITEMS_LIMIT)
                .toList();

        return new PromptMemoryContextDto(
                recentConversation,
                semanticContext,
                proceduralHints,
                personaSnapshot,
                debugTopItems,
                taskThreadSnapshot,
                learnedPreferences
        );
    }

    private String buildRecentConversation(List<ConversationTurn> turns,
                                           int maxChars,
                                           String normalizedQuery,
                                           List<RetrievedMemoryItemDto> candidates) {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            if (!shouldIncludeConversationTurn(turn)) {
                continue;
            }
            String content = normalizeConversationContent(turn);
            if (content.isBlank()) {
                continue;
            }
            String line = turn.role() + ": " + content;
            builder.append(line).append('\n');
            double rel = lexicalOverlap(normalizedQuery, content);
            double rec = recencyDecayHours(ageHours(turn.createdAt()), 24.0);
            boolean userTurn = isUserTurn(turn);
            double relia = userTurn ? 0.78 : 0.58;
            double score = score(rel, rec, relia, userTurn ? 0.26 : 0.12);
            candidates.add(new RetrievedMemoryItemDto(
                    "episodic",
                    line,
                    rel,
                    rec,
                    relia,
                    score,
                    turn.createdAt() == null ? 0L : turn.createdAt().toEpochMilli()
            ));
        }
        return clip(builder.toString().trim(), maxChars);
    }

    private String buildSemanticContext(List<RankedSemanticMemory> entries,
                                        int maxChars,
                                        String normalizedQuery,
                                        List<RetrievedMemoryItemDto> candidates) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int appended = 0;
        for (RankedSemanticMemory ranked : entries) {
            if (!shouldIncludeLayeredEntry(ranked.layer(), appended)) {
                continue;
            }
            SemanticMemoryEntry entry = ranked.entry();
            double rel = Math.max(lexicalOverlap(normalizedQuery, entry.text()), ranked.lexicalScore());
            if (!shouldIncludeSemanticEntry(ranked, rel, appended)) {
                continue;
            }
            String label = semanticContextLabel(ranked);
            String candidateType = semanticCandidateType(ranked);
            String text = humanizeSemanticText(ranked, entry.text());
            builder.append("- ");
            if (!label.isBlank()) {
                builder.append(label).append(' ');
            }
            builder.append(text).append('\n');
            double rec = ranked.recencyScore();
            double relia = semanticReliability(ranked);
            double typeBoost = semanticTypeBoost(ranked);
            double score = "semantic".equals(candidateType)
                    ? score(rel, rec, relia, typeBoost)
                    : semanticRoutingScore(rel, rec, relia, typeBoost);
            candidates.add(new RetrievedMemoryItemDto(
                    candidateType,
                    text,
                    rel,
                    rec,
                    relia,
                    score,
                    entry.createdAt() == null ? 0L : entry.createdAt().toEpochMilli()
            ));
            appended++;
        }
        return clip(builder.toString().trim(), maxChars);
    }

    private boolean shouldIncludeLayeredEntry(MemoryLayer layer, int appended) {
        if (appended < LAYER_PRIORITY_WINDOW) {
            return true;
        }
        return layer == MemoryLayer.FACT || layer == MemoryLayer.WORKING;
    }

    private String buildProceduralHints(List<ProceduralMemoryEntry> history,
                                        List<SkillUsageStats> stats,
                                        int maxChars,
                                        String normalizedQuery,
                                        List<RetrievedMemoryItemDto> candidates) {
        if (history.isEmpty() || stats.isEmpty()) {
            return "";
        }
        Map<String, ProceduralMemoryEntry> latestBySkill = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            latestBySkill.putIfAbsent(entry.skillName(), entry);
        }

        StringBuilder builder = new StringBuilder();
        for (SkillUsageStats stat : stats.stream().sorted(Comparator.comparingLong(SkillUsageStats::successCount).reversed()).toList()) {
            ProceduralMemoryEntry latest = latestBySkill.get(stat.skillName());
            if (latest == null) {
                continue;
            }
            long total = Math.max(1L, stat.totalCount());
            double successRate = stat.successCount() / (double) total;
            double rel = lexicalOverlap(normalizedQuery, stat.skillName() + " " + latest.input());
            double rec = recencyDecayHours(ageHours(latest.createdAt()), 72.0);
            double relia = 0.25 + (0.35 * Math.max(0.0, Math.min(1.0, successRate)));
            double score = proceduralScore(rel, rec, relia);

            String line = "- skill=" + stat.skillName() + ", successRate=" + String.format(Locale.ROOT, "%.2f", successRate);
            builder.append(line).append('\n');
            candidates.add(new RetrievedMemoryItemDto(
                    "procedural",
                    line,
                    rel,
                    rec,
                    relia,
                    score,
                    latest.createdAt() == null ? 0L : latest.createdAt().toEpochMilli()
            ));
        }

        return clip(builder.toString().trim(), maxChars);
    }

    private Map<String, Object> buildPersonaSnapshot(String userId, Map<String, Object> profileContext) {
        PreferenceProfile profile = preferenceProfileService.getProfile(userId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfHasText(snapshot, "assistantName", profile.assistantName());
        putIfHasText(snapshot, "role", profile.role());
        putIfHasText(snapshot, "style", profile.style());
        putIfHasText(snapshot, "language", profile.language());
        putIfHasText(snapshot, "timezone", profile.timezone());
        putIfHasText(snapshot, "preferredChannel", profile.preferredChannel());
        if (profileContext != null && !profileContext.isEmpty()) {
            putIfHasText(snapshot, "assistantName", asText(profileContext.get("assistantName")));
            putIfHasText(snapshot, "role", asText(profileContext.get("role")));
            putIfHasText(snapshot, "style", asText(profileContext.get("style")));
            putIfHasText(snapshot, "language", asText(profileContext.get("language")));
            putIfHasText(snapshot, "timezone", asText(profileContext.get("timezone")));
            putIfHasText(snapshot, "preferredChannel", asText(profileContext.get("preferredChannel")));
        }
        return snapshot;
    }

    private double score(double rel, double rec, double relia, double typeBoost) {
        return 0.55 * rel + 0.25 * rec + 0.15 * relia + 0.05 * typeBoost;
    }

    private double proceduralScore(double rel, double rec, double relia) {
        return 0.62 * rel + 0.18 * rec + 0.12 * relia + 0.08 * 0.18;
    }

    private double semanticRoutingScore(double rel, double rec, double relia, double typeBoost) {
        return 0.38 * rel + 0.18 * rec + 0.14 * relia + 0.05 * typeBoost;
    }

    private double recencyDecayHours(long ageHours, double halfLifeHours) {
        if (ageHours <= 0) {
            return 1.0;
        }
        double decay = Math.exp(-Math.log(2.0) * (ageHours / halfLifeHours));
        return Math.max(0.0, Math.min(1.0, decay));
    }

    private long ageHours(Instant createdAt) {
        if (createdAt == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, Duration.between(createdAt, Instant.now()).toHours());
    }

    private double lexicalOverlap(String normalizedQuery, String text) {
        String normalizedText = normalize(text);
        if (normalizedQuery.isBlank() || normalizedText.isBlank()) {
            return 0.0;
        }
        String[] queryTokens = normalizedQuery.split("\\s+");
        int matched = 0;
        for (String token : queryTokens) {
            if (!token.isBlank() && normalizedText.contains(token)) {
                matched++;
            }
        }
        return queryTokens.length == 0 ? 0.0 : Math.min(1.0, matched / (double) queryTokens.length);
    }

    private boolean shouldIncludeSemanticEntry(RankedSemanticMemory ranked, double relevance, int appended) {
        if (isConversationSummary(ranked)) {
            return relevance >= 0.28 || appended == 0;
        }
        if (isConversationRollup(ranked)) {
            return relevance >= 0.34 || appended == 0;
        }
        if (isSemanticSummary(ranked)) {
            return relevance >= 0.18 || appended < 2;
        }
        if (isLearningSignal(ranked)) {
            return relevance >= 0.18 || appended < 2;
        }
        return true;
    }

    private String semanticContextLabel(RankedSemanticMemory ranked) {
        if (isConversationSummary(ranked)) {
            return "[summary]";
        }
        if (isConversationRollup(ranked)) {
            return "[assistant-context]";
        }
        if (isSemanticSummary(ranked)) {
            return "[summary]";
        }
        if (isLearningSignal(ranked)) {
            return "[assistant-context]";
        }
        return switch (ranked.layer()) {
            case FACT -> "[fact]";
            case WORKING -> "[working]";
            case BUFFER -> "[buffer]";
            case SEMANTIC -> "";
        };
    }

    private String semanticCandidateType(RankedSemanticMemory ranked) {
        if (isConversationSummary(ranked)) {
            return "semantic-summary";
        }
        return (isConversationRollup(ranked) || isSemanticSummary(ranked) || isLearningSignal(ranked))
                ? "semantic-routing"
                : "semantic";
    }

    private double semanticReliability(RankedSemanticMemory ranked) {
        if (isConversationSummary(ranked)) {
            return 0.32;
        }
        if (isConversationRollup(ranked)) {
            return 0.36;
        }
        if (isSemanticSummary(ranked)) {
            return 0.46;
        }
        if (isLearningSignal(ranked)) {
            return 0.42;
        }
        return switch (ranked.layer()) {
            case FACT -> 0.9;
            case WORKING -> 0.82;
            case BUFFER -> 0.78;
            case SEMANTIC -> 0.75;
        };
    }

    private double semanticTypeBoost(RankedSemanticMemory ranked) {
        if (isConversationSummary(ranked)) {
            return 0.14;
        }
        if (isConversationRollup(ranked)) {
            return 0.12;
        }
        if (isSemanticSummary(ranked)) {
            return 0.16;
        }
        if (isLearningSignal(ranked)) {
            return 0.14;
        }
        return 0.6;
    }

    private boolean isConversationRollup(RankedSemanticMemory ranked) {
        String bucket = ranked == null || ranked.bucket() == null ? "" : ranked.bucket().trim().toLowerCase(Locale.ROOT);
        return CONVERSATION_ROLLUP_BUCKET.equals(bucket);
    }

    private boolean isConversationSummary(RankedSemanticMemory ranked) {
        String text = ranked == null || ranked.entry() == null ? "" : ranked.entry().text();
        return text.contains(CONVERSATION_SUMMARY_MARKER) || text.contains(REVIEW_FOCUS_MARKER);
    }

    private boolean isSemanticSummary(RankedSemanticMemory ranked) {
        String text = ranked == null || ranked.entry() == null ? "" : ranked.entry().text();
        return text.contains(INTENT_SUMMARY_MARKER) || normalize(text).startsWith(normalize(SEMANTIC_SUMMARY_PREFIX));
    }

    private boolean isLearningSignal(RankedSemanticMemory ranked) {
        String text = ranked == null || ranked.entry() == null ? "" : ranked.entry().text();
        return text.contains(LEARNING_SIGNAL_MARKER);
    }

    private String humanizeSemanticText(RankedSemanticMemory ranked, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (!(isSemanticSummary(ranked) || isConversationRollup(ranked) || isConversationSummary(ranked)
                || isLearningSignal(ranked) || text.contains(TASK_STATE_MARKER) || text.contains(TASK_FACT_MARKER))) {
            return text;
        }
        return text.replace(INTENT_SUMMARY_MARKER, "")
                .replace(ASSISTANT_CONTEXT_MARKER, "")
                .replace(TASK_FACT_MARKER, "")
                .replace(TASK_STATE_MARKER, "")
                .replace(LEARNING_SIGNAL_MARKER, "")
                .replace(CONVERSATION_SUMMARY_MARKER, "")
                .replace(REVIEW_FOCUS_MARKER, "")
                .replace("semantic-summary", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:;；,，\\-\\s]+", "")
                .trim();
    }

    private TaskThreadSnapshotDto buildTaskThreadSnapshot(List<RankedSemanticMemory> entries) {
        if (entries == null || entries.isEmpty()) {
            return TaskThreadSnapshotDto.empty();
        }
        TaskThreadBuilder builder = new TaskThreadBuilder();
        for (RankedSemanticMemory ranked : entries) {
            if (!isTaskThreadSource(ranked)) {
                continue;
            }
            ingestTaskThreadText(ranked.entry().text(), builder);
        }
        return builder.toSnapshot();
    }

    private Map<String, Object> buildLearnedPreferences(TaskThreadSnapshotDto snapshot,
                                                        List<RankedSemanticMemory> entries) {
        Map<String, Object> preferences = new LinkedHashMap<>();
        if (snapshot != null && !snapshot.preferenceHint().isBlank()) {
            deriveStablePreferences(snapshot.preferenceHint(), preferences);
        }
        if (entries != null) {
            for (RankedSemanticMemory ranked : entries) {
                if (ranked == null || ranked.entry() == null || ranked.entry().text() == null) {
                    continue;
                }
                if (isLearningSignal(ranked)) {
                    deriveStablePreferences(ranked.entry().text(), preferences);
                }
            }
        }
        return preferences.isEmpty() ? Map.of() : Map.copyOf(preferences);
    }

    private boolean isTaskThreadSource(RankedSemanticMemory ranked) {
        if (ranked == null || ranked.entry() == null || ranked.entry().text() == null) {
            return false;
        }
        String text = ranked.entry().text();
        return text.contains(TASK_FACT_MARKER)
                || text.contains(TASK_STATE_MARKER)
                || text.contains(LEARNING_SIGNAL_MARKER);
    }

    private void ingestTaskThreadText(String rawText, TaskThreadBuilder builder) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        for (String rawLine : rawText.trim().split("\\R")) {
            String line = stripTaskMarkers(rawLine);
            if (line.isBlank() || line.endsWith(":") || "none".equalsIgnoreCase(line)) {
                continue;
            }
            for (String fragment : line.split("[；;]")) {
                String candidate = stripTaskMarkers(fragment);
                if (candidate.isBlank()) {
                    continue;
                }
                assignTaskValue(builder, "当前事项", candidate);
                assignTaskValue(builder, "任务", candidate);
                assignTaskValue(builder, "事项", candidate);
                assignTaskValue(builder, "状态", candidate);
                assignTaskValue(builder, "下一步", candidate);
                assignTaskValue(builder, "项目", candidate);
                assignTaskValue(builder, "主题", candidate);
                assignTaskValue(builder, "截止时间", candidate);
                assignTaskValue(builder, "偏好", candidate);
            }
        }
    }

    private void assignTaskValue(TaskThreadBuilder builder, String label, String fragment) {
        if (builder == null || fragment == null || fragment.isBlank()) {
            return;
        }
        String prefix = label + "：";
        int index = fragment.indexOf(prefix);
        if (index < 0) {
            return;
        }
        String value = fragment.substring(index + prefix.length()).trim();
        if (value.isBlank()) {
            return;
        }
        switch (label) {
            case "当前事项", "任务", "事项" -> builder.focus = firstNonBlank(builder.focus, value);
            case "状态" -> builder.state = firstNonBlank(builder.state, value);
            case "下一步" -> builder.nextAction = firstNonBlank(builder.nextAction, value);
            case "项目" -> builder.project = firstNonBlank(builder.project, value);
            case "主题" -> builder.topic = firstNonBlank(builder.topic, value);
            case "截止时间" -> builder.dueDate = firstNonBlank(builder.dueDate, value);
            case "偏好" -> builder.preferenceHint = firstNonBlank(builder.preferenceHint, value);
            default -> {
            }
        }
    }

    private void deriveStablePreferences(String rawText, Map<String, Object> preferences) {
        String normalized = normalize(rawText);
        if (normalized.isBlank()) {
            return;
        }
        if (!preferences.containsKey("clarifyStyle")
                && (normalized.contains("少澄清") || (normalized.contains("直接推进") && normalized.contains("上下文明确")))) {
            preferences.put("clarifyStyle", "minimal");
        }
        if (!preferences.containsKey("planningStyle")
                && (normalized.contains("先给结构化推进方案") || normalized.contains("先给结构化") || normalized.contains("先给方案"))) {
            preferences.put("planningStyle", "plan-first");
        }
        if (!preferences.containsKey("blockerStyle")
                && (normalized.contains("先定位卡点") || normalized.contains("定位卡点"))) {
            preferences.put("blockerStyle", "locate-blocker-first");
        }
        if (!preferences.containsKey("threadStyle")
                && (normalized.contains("围绕同一事项推进") || normalized.contains("继续围绕同一事项"))) {
            preferences.put("threadStyle", "continue");
        }
        if (!preferences.containsKey("executionStyle")
                && (normalized.contains("直接推进") || normalized.contains("继续推进"))) {
            preferences.put("executionStyle", "direct-progress");
        }
    }

    private String stripTaskMarkers(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.trim()
                .replace(TASK_FACT_MARKER, "")
                .replace(TASK_STATE_MARKER, "")
                .replace(LEARNING_SIGNAL_MARKER, "")
                .replace("[fact]", "")
                .replace("[working]", "")
                .replace("[assistant-context]", "")
                .replace("[summary]", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:;；,，\\-\\s]+", "")
                .trim();
    }

    private String firstNonBlank(String current, String candidate) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return candidate == null ? "" : candidate.trim();
    }

    private boolean shouldIncludeConversationTurn(ConversationTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return false;
        }
        return isUserTurn(turn) || !looksLikeInternalAssistantContent(turn.content());
    }

    private boolean isUserTurn(ConversationTurn turn) {
        return turn != null && "user".equalsIgnoreCase(turn.role());
    }

    private String normalizeConversationContent(ConversationTurn turn) {
        if (turn == null || turn.content() == null) {
            return "";
        }
        String content = turn.content().trim();
        int maxChars = isUserTurn(turn) ? 220 : 180;
        return clip(content, maxChars);
    }

    private boolean looksLikeInternalAssistantContent(String content) {
        String normalized = normalize(content);
        return normalized.contains(normalize("根据已有记忆，我先直接回答"))
                || normalized.contains(normalize(INTENT_SUMMARY_MARKER))
                || normalized.contains(normalize(ASSISTANT_CONTEXT_MARKER))
                || normalized.contains(normalize(CONVERSATION_SUMMARY_MARKER))
                || normalized.contains(normalize(REVIEW_FOCUS_MARKER))
                || normalized.contains(normalize("semantic-summary"))
                || normalized.contains(normalize("reply="))
                || normalized.contains(normalize("Recent conversation:"))
                || normalized.contains(normalize("Relevant knowledge:"))
                || normalized.contains(normalize("User skill habits:"))
                || (normalized.contains("intent") && normalized.contains("channel"));
    }

    private boolean isConversationalQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return normalizedQuery.contains("聊天")
                || normalizedQuery.contains("聊聊")
                || normalizedQuery.contains("日常")
                || normalizedQuery.contains("在吗")
                || normalizedQuery.contains("你好")
                || normalizedQuery.contains("哈喽")
                || normalizedQuery.contains("谢谢")
                || normalizedQuery.contains("啥呀")
                || normalizedQuery.contains("可以可以")
                || normalizedQuery.contains("好的")
                || normalizedQuery.contains("晚安")
                || normalizedQuery.contains("早安");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private String clip(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        if (maxChars <= 1) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 1) + "...";
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        target.put(key, trimmed);
    }

    private static final class TaskThreadBuilder {
        private String focus = "";
        private String state = "";
        private String nextAction = "";
        private String project = "";
        private String topic = "";
        private String dueDate = "";
        private String preferenceHint = "";

        private TaskThreadSnapshotDto toSnapshot() {
            String summary = summary();
            if (focus.isBlank() && state.isBlank() && nextAction.isBlank() && project.isBlank()
                    && topic.isBlank() && dueDate.isBlank() && preferenceHint.isBlank()) {
                return TaskThreadSnapshotDto.empty();
            }
            return new TaskThreadSnapshotDto(
                    focus,
                    state,
                    nextAction,
                    project,
                    topic,
                    dueDate,
                    preferenceHint,
                    summary
            );
        }

        private String summary() {
            StringBuilder builder = new StringBuilder();
            appendSummary(builder, "当前事项", focus);
            appendSummary(builder, "状态", state);
            appendSummary(builder, "下一步", nextAction);
            appendSummary(builder, "主题", topic);
            return builder.toString();
        }

        private void appendSummary(StringBuilder builder, String label, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(label).append(" ").append(value.trim());
        }
    }
}

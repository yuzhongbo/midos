package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class DefaultStrategyAgent implements StrategyAgent {

    private static final List<String> SCHEDULE_KEYWORDS = List.of("排课", "课表", "课程", "日程", "预约", "时间冲突", "冲突", "schedule", "calendar");
    private static final List<String> FAILURE_KEYWORDS = List.of("失败", "错误", "异常", "重试", "重做", "卡住", "阻塞", "missing", "缺失", "校验", "validate", "error", "fail");
    private static final List<String> EFFICIENCY_KEYWORDS = List.of("重复", "手工", "耗时", "慢", "低效", "重复调整", "重复确认", "拖延", "效率", "manual", "slow", "delay");

    private final MemoryGateway memoryGateway;
    private final String semanticBucket;
    private final String proceduralSkillName;
    private final int historyWindow;
    private final int minHighFrequencyCount;
    private final int minFailureCount;
    private final int maxActions;

    public DefaultStrategyAgent() {
        this.memoryGateway = null;
        this.semanticBucket = "strategy.longterm";
        this.proceduralSkillName = "strategy.agent";
        this.historyWindow = 8;
        this.minHighFrequencyCount = 5;
        this.minFailureCount = 3;
        this.maxActions = 3;
    }

    public DefaultStrategyAgent(MemoryGateway memoryGateway) {
        this.memoryGateway = memoryGateway;
        this.semanticBucket = "strategy.longterm";
        this.proceduralSkillName = "strategy.agent";
        this.historyWindow = 8;
        this.minHighFrequencyCount = 5;
        this.minFailureCount = 3;
        this.maxActions = 3;
    }

    @Autowired
    public DefaultStrategyAgent(MemoryGateway memoryGateway,
                                @Value("${mindos.autonomous.strategy.semantic-bucket:strategy.longterm}") String semanticBucket,
                                @Value("${mindos.autonomous.strategy.procedural-skill:strategy.agent}") String proceduralSkillName,
                                @Value("${mindos.autonomous.strategy.recent-history-turns:8}") int historyWindow,
                                @Value("${mindos.autonomous.strategy.min-high-frequency-count:5}") int minHighFrequencyCount,
                                @Value("${mindos.autonomous.strategy.min-failure-count:3}") int minFailureCount,
                                @Value("${mindos.autonomous.strategy.max-actions:3}") int maxActions) {
        this.memoryGateway = memoryGateway;
        this.semanticBucket = normalizeText(semanticBucket).isBlank() ? "strategy.longterm" : semanticBucket.trim();
        this.proceduralSkillName = normalizeText(proceduralSkillName).isBlank() ? "strategy.agent" : proceduralSkillName.trim();
        this.historyWindow = Math.max(1, historyWindow);
        this.minHighFrequencyCount = Math.max(1, minHighFrequencyCount);
        this.minFailureCount = Math.max(1, minFailureCount);
        this.maxActions = Math.max(1, maxActions);
    }

    @Override
    public StrategicGoal strategize(String userId) {
        String safeUserId = normalizeUserId(userId);
        StrategySignals signals = analyse(safeUserId);

        List<StrategicGoal> candidates = new ArrayList<>();
        addIfPresent(candidates, buildScheduleGoal(signals));
        addIfPresent(candidates, buildFailureGoal(signals));
        addIfPresent(candidates, buildEfficiencyGoal(signals));
        addIfPresent(candidates, buildFrequencyGoal(signals));

        if (candidates.isEmpty()) {
            candidates.add(fallbackGoal(signals));
        }

        Map<String, StrategicGoal> unique = new LinkedHashMap<>();
        for (StrategicGoal candidate : candidates) {
            if (candidate == null || candidate.goal().isBlank()) {
                continue;
            }
            unique.putIfAbsent(candidate.goal(), candidate);
        }

        StrategicGoal selected = unique.values().stream()
                .sorted(Comparator
                        .comparingDouble(StrategicGoal::priority).reversed()
                        .thenComparing(StrategicGoal::generatedAt, Comparator.reverseOrder())
                        .thenComparing(StrategicGoal::goal))
                .findFirst()
                .orElseGet(() -> fallbackGoal(signals));

        writeMemory(safeUserId, selected, signals);
        return selected;
    }

    private StrategySignals analyse(String userId) {
        List<ConversationTurn> history = historyTail(memoryGateway == null ? List.of() : safeHistory(memoryGateway.recentHistory(userId)), historyWindow);
        List<SkillUsageStats> stats = safeStats(memoryGateway == null ? List.of() : memoryGateway.skillUsageStats(userId));

        int scheduleMentions = countMentions(history, SCHEDULE_KEYWORDS);
        int failureMentions = countMentions(history, FAILURE_KEYWORDS);
        int efficiencyMentions = countMentions(history, EFFICIENCY_KEYWORDS);
        SkillUsageStats scheduleSkill = bestMatchingSkill(stats, SCHEDULE_KEYWORDS);
        SkillUsageStats failureSkill = worstFailureSkill(stats);
        SkillUsageStats frequencySkill = bestFrequencySkill(stats);
        String historySummary = summarizeHistory(history);
        return new StrategySignals(
                history,
                stats,
                scheduleMentions,
                failureMentions,
                efficiencyMentions,
                scheduleSkill,
                failureSkill,
                frequencySkill,
                historySummary
        );
    }

    private StrategicGoal buildScheduleGoal(StrategySignals signals) {
        boolean scheduleSignal = signals.scheduleMentions() > 0 || signals.scheduleSkill() != null;
        if (!scheduleSignal) {
            return null;
        }
        double failureRate = signals.scheduleSkill() == null ? 0.0 : failureRate(signals.scheduleSkill());
        double frequencyBonus = signals.frequencySkill() == null ? 0.0 : Math.min(0.15, signals.frequencySkill().totalCount() / 50.0);
        double priority = clamp(0.68
                + Math.min(0.18, signals.scheduleMentions() * 0.06)
                + Math.min(0.12, failureRate * 0.18)
                + frequencyBonus
                + Math.min(0.06, signals.efficiencyMentions() * 0.02));
        List<String> actions = limitActions(List.of(
                "优化排课算法",
                "增加自动冲突检测",
                "支持冲突重排与通知"
        ));
        List<String> reasons = new ArrayList<>();
        reasons.add("scheduleMentions=" + signals.scheduleMentions());
        reasons.add("efficiencyMentions=" + signals.efficiencyMentions());
        if (signals.scheduleSkill() != null) {
            reasons.add("scheduleSkill=" + signals.scheduleSkill().skillName());
            reasons.add("scheduleFailureRate=" + round(failureRate(signals.scheduleSkill())));
        }
        if (!signals.historySummary().isBlank()) {
            reasons.add("historyHint=" + truncate(signals.historySummary(), 160));
        }
        return new StrategicGoal("减少排课冲突", priority, actions, reasons, Instant.now());
    }

    private StrategicGoal buildFailureGoal(StrategySignals signals) {
        SkillUsageStats failureSkill = signals.failureSkill();
        if (failureSkill == null) {
            return null;
        }
        double failureRate = failureRate(failureSkill);
        if (failureSkill.failureCount() < minFailureCount && failureRate < 0.25 && signals.failureMentions() == 0) {
            return null;
        }
        double priority = clamp(0.56
                + Math.min(0.24, failureRate * 0.34)
                + Math.min(0.10, failureSkill.failureCount() / 20.0)
                + Math.min(0.08, signals.failureMentions() * 0.03));
        List<String> actions = limitActions(failureActionsForSkill(failureSkill.skillName()));
        List<String> reasons = new ArrayList<>();
        reasons.add("skill=" + failureSkill.skillName());
        reasons.add("failureRate=" + round(failureRate));
        reasons.add("failureCount=" + failureSkill.failureCount());
        reasons.add("totalCount=" + failureSkill.totalCount());
        if (!signals.historySummary().isBlank()) {
            reasons.add("historyHint=" + truncate(signals.historySummary(), 120));
        }
        return new StrategicGoal("降低高频失败流程", priority, actions, reasons, Instant.now());
    }

    private StrategicGoal buildEfficiencyGoal(StrategySignals signals) {
        if (signals.efficiencyMentions() <= 0 && signals.history().size() < 3) {
            return null;
        }
        double priority = clamp(0.50
                + Math.min(0.22, signals.efficiencyMentions() * 0.08)
                + Math.min(0.10, signals.history().size() / 20.0)
                + Math.min(0.08, signals.frequencySkill() == null ? 0.0 : signals.frequencySkill().totalCount() / 80.0));
        List<String> actions = limitActions(List.of(
                "自动化高频步骤",
                "减少人工确认",
                "对低效环节做并行或缓存处理"
        ));
        List<String> reasons = new ArrayList<>();
        reasons.add("efficiencyMentions=" + signals.efficiencyMentions());
        reasons.add("historyTurns=" + signals.history().size());
        if (!signals.historySummary().isBlank()) {
            reasons.add("historyHint=" + truncate(signals.historySummary(), 160));
        }
        return new StrategicGoal("减少重复手工操作", priority, actions, reasons, Instant.now());
    }

    private StrategicGoal buildFrequencyGoal(StrategySignals signals) {
        SkillUsageStats frequencySkill = signals.frequencySkill();
        if (frequencySkill == null) {
            return null;
        }
        double successRate = successRate(frequencySkill);
        if (frequencySkill.totalCount() < minHighFrequencyCount || successRate < 0.70) {
            return null;
        }
        double priority = clamp(0.52
                + Math.min(0.22, successRate * 0.25)
                + Math.min(0.18, frequencySkill.totalCount() / 40.0)
                + Math.min(0.06, signals.failureMentions() * 0.02));
        List<String> actions = limitActions(List.of(
                "提炼为可复用流程",
                "自动补全常用参数",
                "写入 procedural memory 供复用"
        ));
        List<String> reasons = new ArrayList<>();
        reasons.add("skill=" + frequencySkill.skillName());
        reasons.add("totalCount=" + frequencySkill.totalCount());
        reasons.add("successRate=" + round(successRate));
        return new StrategicGoal("固化高频行为", priority, actions, reasons, Instant.now());
    }

    private StrategicGoal fallbackGoal(StrategySignals signals) {
        List<String> actions = limitActions(List.of(
                "持续收集高频任务",
                "记录失败样本",
                "定期复盘长期行为"
        ));
        List<String> reasons = new ArrayList<>();
        reasons.add("fallback");
        reasons.add("historyTurns=" + signals.history().size());
        reasons.add("skillStats=" + signals.stats().size());
        if (!signals.historySummary().isBlank()) {
            reasons.add("historyHint=" + truncate(signals.historySummary(), 120));
        }
        return new StrategicGoal("建立长期行为基线", 0.35, actions, reasons, Instant.now());
    }

    private void writeMemory(String userId, StrategicGoal goal, StrategySignals signals) {
        if (memoryGateway == null || userId.isBlank() || goal == null) {
            return;
        }
        String summary = buildSemanticText(goal, signals);
        memoryGateway.writeSemantic(userId, summary, buildEmbedding(goal, signals), semanticBucket);
        memoryGateway.writeProcedural(userId, ProceduralMemoryEntry.of(proceduralSkillName, summary, true));
    }

    private String buildSemanticText(StrategicGoal goal, StrategySignals signals) {
        return "strategy | goal=" + goal.goal()
                + " | priority=" + round(goal.priority())
                + " | actions=" + String.join("; ", goal.actions())
                + " | reasons=" + String.join("; ", goal.reasons())
                + " | scheduleMentions=" + signals.scheduleMentions()
                + " | failureMentions=" + signals.failureMentions()
                + " | efficiencyMentions=" + signals.efficiencyMentions();
    }

    private List<Double> buildEmbedding(StrategicGoal goal, StrategySignals signals) {
        return List.of(
                goal.priority(),
                normalizedCount(goal.actions().size(), maxActions),
                normalizedCount(signals.scheduleMentions(), 6),
                normalizedCount(signals.failureMentions(), 6),
                normalizedCount(signals.efficiencyMentions(), 6),
                signals.frequencySkill() == null ? 0.0 : successRate(signals.frequencySkill()),
                signals.failureSkill() == null ? 0.0 : failureRate(signals.failureSkill())
        );
    }

    private List<String> failureActionsForSkill(String skillName) {
        String normalized = normalizeText(skillName).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, SCHEDULE_KEYWORDS)) {
            return List.of("优化排课算法", "增加自动冲突检测", "支持冲突重排与通知");
        }
        if (containsAny(normalized, "param", "validate", "input", "missing", "缺失", "校验")) {
            return List.of("增加参数校验", "自动补全缺失字段", "记录失败模式并持续优化");
        }
        if (containsAny(normalized, "memory", "semantic", "persona", "procedural", "history")) {
            return List.of("补齐记忆上下文", "先读取 Persona / Semantic / Procedural Memory", "把缺失约束写回上下文");
        }
        return List.of("增加参数校验", "增加 fallback/retry", "记录失败模式并持续优化");
    }

    private List<String> limitActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String action : actions) {
            if (action == null) {
                continue;
            }
            String trimmed = action.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
            if (normalized.size() >= maxActions) {
                break;
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private List<ConversationTurn> safeHistory(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream().filter(Objects::nonNull).toList();
    }

    private List<SkillUsageStats> safeStats(List<SkillUsageStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return List.of();
        }
        return stats.stream().filter(Objects::nonNull).toList();
    }

    private List<ConversationTurn> historyTail(List<ConversationTurn> history, int window) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int safeWindow = Math.max(1, window);
        int fromIndex = Math.max(0, history.size() - safeWindow);
        return List.copyOf(history.subList(fromIndex, history.size()));
    }

    private int countMentions(List<ConversationTurn> history, List<String> keywords) {
        if (history == null || history.isEmpty() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ConversationTurn turn : history) {
            String content = turn == null ? "" : normalizeText(turn.content()).toLowerCase(Locale.ROOT);
            if (containsAny(content, keywords)) {
                count++;
            }
        }
        return count;
    }

    private SkillUsageStats bestMatchingSkill(List<SkillUsageStats> stats, List<String> keywords) {
        if (stats == null || stats.isEmpty() || keywords == null || keywords.isEmpty()) {
            return null;
        }
        return stats.stream()
                .filter(stat -> stat.skillName() != null && containsAny(stat.skillName().toLowerCase(Locale.ROOT), keywords))
                .max(Comparator
                        .comparingLong(SkillUsageStats::totalCount)
                        .thenComparingLong(SkillUsageStats::failureCount)
                        .thenComparing(SkillUsageStats::skillName))
                .orElse(null);
    }

    private SkillUsageStats bestFrequencySkill(List<SkillUsageStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        return stats.stream()
                .filter(stat -> stat.skillName() != null && !stat.skillName().isBlank())
                .max(Comparator
                        .comparingLong(SkillUsageStats::totalCount)
                        .thenComparingDouble(this::successRate)
                        .thenComparing(SkillUsageStats::skillName))
                .orElse(null);
    }

    private SkillUsageStats worstFailureSkill(List<SkillUsageStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        return stats.stream()
                .filter(stat -> stat.skillName() != null && !stat.skillName().isBlank() && stat.failureCount() > 0)
                .max(Comparator
                        .comparingDouble(this::failureRate)
                        .thenComparingLong(SkillUsageStats::failureCount)
                        .thenComparingLong(SkillUsageStats::totalCount)
                        .thenComparing(SkillUsageStats::skillName))
                .orElse(null);
    }

    private double successRate(SkillUsageStats stats) {
        if (stats == null || stats.totalCount() <= 0L) {
            return 0.0;
        }
        return clamp(stats.successCount() / (double) stats.totalCount());
    }

    private double failureRate(SkillUsageStats stats) {
        if (stats == null || stats.totalCount() <= 0L) {
            return 0.0;
        }
        return clamp(stats.failureCount() / (double) stats.totalCount());
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (text.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.length == 0) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (normalized.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String summarizeHistory(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(turn.role()).append(": ").append(truncate(turn.content(), 80));
        }
        return builder.toString();
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "local-user" : userId.trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private double normalizedCount(long count, long limit) {
        if (limit <= 0L) {
            return 0.0;
        }
        return clamp(count / (double) limit);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private void addIfPresent(List<StrategicGoal> candidates, StrategicGoal goal) {
        if (candidates == null || goal == null || goal.goal().isBlank()) {
            return;
        }
        candidates.add(goal);
    }

    private record StrategySignals(List<ConversationTurn> history,
                                   List<SkillUsageStats> stats,
                                   int scheduleMentions,
                                   int failureMentions,
                                   int efficiencyMentions,
                                   SkillUsageStats scheduleSkill,
                                   SkillUsageStats failureSkill,
                                   SkillUsageStats frequencySkill,
                                   String historySummary) {

        private StrategySignals {
            history = history == null ? List.of() : List.copyOf(history);
            stats = stats == null ? List.of() : List.copyOf(stats);
            scheduleMentions = Math.max(0, scheduleMentions);
            failureMentions = Math.max(0, failureMentions);
            efficiencyMentions = Math.max(0, efficiencyMentions);
            historySummary = historySummary == null ? "" : historySummary.trim();
        }
    }
}

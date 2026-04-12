package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.LongTaskService;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultGoalGenerator implements GoalGenerator {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final LongTaskService longTaskService;
    private final StrategyAgent strategyAgent;
    private final int maxGoals;
    private final int recentHistoryTurns;

    public DefaultGoalGenerator() {
        this((DispatcherMemoryFacade) null, null, null, 5, 6);
    }

    public DefaultGoalGenerator(MemoryGateway memoryGateway,
                                LongTaskService longTaskService) {
        this(new DispatcherMemoryFacade(memoryGateway, null, null), longTaskService, null, 5, 6);
    }

    public DefaultGoalGenerator(MemoryGateway memoryGateway,
                                LongTaskService longTaskService,
                                int maxGoals,
                                int recentHistoryTurns) {
        this(new DispatcherMemoryFacade(memoryGateway, null, null), longTaskService, null, maxGoals, recentHistoryTurns);
    }

    public DefaultGoalGenerator(MemoryGateway memoryGateway,
                                LongTaskService longTaskService,
                                StrategyAgent strategyAgent,
                                int maxGoals,
                                int recentHistoryTurns) {
        this(new DispatcherMemoryFacade(memoryGateway, null, null), longTaskService, strategyAgent, maxGoals, recentHistoryTurns);
    }

    @Autowired
    public DefaultGoalGenerator(DispatcherMemoryFacade dispatcherMemoryFacade,
                                LongTaskService longTaskService,
                                StrategyAgent strategyAgent,
                                @Value("${mindos.autonomous.goal.max-goals:5}") int maxGoals,
                                @Value("${mindos.autonomous.goal.recent-history-turns:6}") int recentHistoryTurns) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade == null
                ? new DispatcherMemoryFacade(null, null, null)
                : dispatcherMemoryFacade;
        this.longTaskService = longTaskService;
        this.strategyAgent = strategyAgent;
        this.maxGoals = Math.max(1, maxGoals);
        this.recentHistoryTurns = Math.max(1, recentHistoryTurns);
    }

    @Override
    public List<AutonomousGoal> generate(String userId, int limit) {
        String safeUserId = normalizeUserId(userId);
        int effectiveLimit = Math.max(1, Math.min(maxGoals, limit));

        List<AutonomousGoal> goals = new ArrayList<>();
        goals.addAll(generateLongTaskGoals(safeUserId));
        goals.addAll(generateBehaviorOptimizationGoals(safeUserId));
        goals.addAll(generateStrategicGoals(safeUserId));
        goals.addAll(generateSkillRecoveryGoals(safeUserId));
        goals.addAll(generateMemoryReviewGoals(safeUserId));

        if (goals.isEmpty()) {
            goals.add(fallbackGoal(safeUserId));
        }

        Map<String, AutonomousGoal> unique = new LinkedHashMap<>();
        for (AutonomousGoal goal : goals) {
            if (goal == null || goal.goalId().isBlank()) {
                continue;
            }
            unique.putIfAbsent(goal.goalId(), goal);
        }

        return unique.values().stream()
                .sorted(Comparator
                        .comparingInt(AutonomousGoal::priority).reversed()
                        .thenComparing(AutonomousGoal::generatedAt, Comparator.reverseOrder())
                        .thenComparing(AutonomousGoal::goalId))
                .limit(effectiveLimit)
                .toList();
    }

    private List<AutonomousGoal> generateStrategicGoals(String userId) {
        if (strategyAgent == null) {
            return List.of();
        }
        StrategicGoal strategicGoal = strategyAgent.generate(userId);
        if (strategicGoal == null || strategicGoal.goal().isBlank()) {
            return List.of();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("strategyGoal", strategicGoal.goal());
        params.put("strategyPriority", strategicGoal.priority());
        params.put("strategyActions", strategicGoal.actions());
        params.put("strategyReasons", strategicGoal.reasons());

        List<String> reasons = new ArrayList<>(strategicGoal.reasons());
        if (reasons.isEmpty()) {
            reasons.add("strategy-agent");
        }

        int priority = Math.max(0, Math.min(100, (int) Math.round(45.0 + strategicGoal.priority() * 35.0)));
        return List.of(new AutonomousGoal(
                "strategy:" + strategicGoal.goal(),
                AutonomousGoalType.STRATEGIC,
                "长期战略：" + strategicGoal.goal(),
                strategicGoal.goal(),
                "llm.orchestrate",
                "strategy.agent",
                priority,
                params,
                reasons,
                strategicGoal.generatedAt()
        ));
    }

    private List<AutonomousGoal> generateLongTaskGoals(String userId) {
        if (longTaskService == null) {
            return List.of();
        }
        List<LongTask> tasks = longTaskService.listTasks(userId, null);
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<AutonomousGoal> goals = new ArrayList<>();
        for (LongTask task : tasks) {
            if (task == null || task.status() == null || task.status().isTerminal()) {
                continue;
            }
            String focusStep = firstNonBlank(
                    task.pendingSteps() == null || task.pendingSteps().isEmpty() ? "" : task.pendingSteps().get(0),
                    task.objective(),
                    task.title(),
                    "llm.orchestrate"
            );
            int priority = scoreLongTask(task, now);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("taskId", task.taskId());
            params.put("taskTitle", task.title());
            params.put("taskObjective", task.objective());
            params.put("taskStatus", task.status().name());
            params.put("taskProgressPercent", task.progressPercent());
            params.put("taskPendingSteps", task.pendingSteps());
            params.put("taskCompletedSteps", task.completedSteps());
            params.put("taskDueAt", task.dueAt());
            params.put("taskNextCheckAt", task.nextCheckAt());
            params.put("taskFocusStep", focusStep);
            params.put("taskCompletable", task.pendingSteps() != null && task.pendingSteps().size() <= 1);
            List<String> reasons = new ArrayList<>();
            reasons.add("status=" + task.status().name().toLowerCase(Locale.ROOT));
            reasons.add("progress=" + task.progressPercent());
            if (task.dueAt() != null) {
                reasons.add("dueAt=" + task.dueAt());
            }
            if (task.nextCheckAt() != null) {
                reasons.add("nextCheckAt=" + task.nextCheckAt());
            }
            goals.add(new AutonomousGoal(
                    "task:" + task.taskId(),
                    AutonomousGoalType.LONG_TASK,
                    "推进长期任务：" + firstNonBlank(task.title(), task.objective(), task.taskId()),
                    "完成或推进长期任务：" + firstNonBlank(task.objective(), task.title(), task.taskId()),
                    focusStep,
                    task.taskId(),
                    priority,
                    params,
                    reasons,
                    Instant.now()
            ));
        }
        return goals;
    }

    private List<AutonomousGoal> generateSkillRecoveryGoals(String userId) {
        List<SkillUsageStats> statsList = dispatcherMemoryFacade.getSkillUsageStats(userId);
        if (statsList == null || statsList.isEmpty()) {
            return List.of();
        }
        List<AutonomousGoal> goals = new ArrayList<>();
        for (SkillUsageStats stats : statsList) {
            if (stats == null || stats.skillName() == null || stats.skillName().isBlank()) {
                continue;
            }
            if (stats.failureCount() <= 0L && stats.totalCount() < 3L) {
                continue;
            }
            double failureRate = stats.totalCount() <= 0L ? 0.0 : stats.failureCount() / (double) stats.totalCount();
            int priority = 55
                    + (int) Math.round(failureRate * 30.0)
                    + (int) Math.min(15L, stats.totalCount() * 2L);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("skillName", stats.skillName());
            params.put("skillTotalCount", stats.totalCount());
            params.put("skillSuccessCount", stats.successCount());
            params.put("skillFailureCount", stats.failureCount());
            params.put("skillFailureRate", failureRate);
            List<String> reasons = new ArrayList<>();
            reasons.add("failureRate=" + round(failureRate));
            reasons.add("totalCount=" + stats.totalCount());
            reasons.add("failureCount=" + stats.failureCount());
            goals.add(new AutonomousGoal(
                    "skill:" + stats.skillName(),
                    AutonomousGoalType.SKILL_RECOVERY,
                    "修复技能波动：" + stats.skillName(),
                    "降低技能 " + stats.skillName() + " 的失败率，并提炼改进方法",
                    stats.skillName(),
                    stats.skillName(),
                    priority,
                    params,
                    reasons,
                    Instant.now()
            ));
        }
        return goals;
    }

    private List<AutonomousGoal> generateBehaviorOptimizationGoals(String userId) {
        List<SkillUsageStats> statsList = dispatcherMemoryFacade.getSkillUsageStats(userId);
        if (statsList == null || statsList.isEmpty()) {
            return List.of();
        }
        List<AutonomousGoal> goals = new ArrayList<>();
        for (SkillUsageStats stats : statsList) {
            if (stats == null || stats.skillName() == null || stats.skillName().isBlank()) {
                continue;
            }
            if (stats.totalCount() < 5L) {
                continue;
            }
            double successRate = stats.totalCount() <= 0L ? 0.0 : stats.successCount() / (double) stats.totalCount();
            if (successRate < 0.7) {
                continue;
            }
            int priority = 52
                    + (int) Math.min(20L, stats.totalCount() * 2L)
                    + (int) Math.round(successRate * 12.0);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("behaviorName", stats.skillName());
            params.put("behaviorTotalCount", stats.totalCount());
            params.put("behaviorSuccessCount", stats.successCount());
            params.put("behaviorFailureCount", stats.failureCount());
            params.put("behaviorSuccessRate", successRate);
            List<String> reasons = new ArrayList<>();
            reasons.add("frequency=" + stats.totalCount());
            reasons.add("successRate=" + round(successRate));
            reasons.add("optimize=" + stats.skillName());
            goals.add(new AutonomousGoal(
                    "behavior:" + stats.skillName(),
                    AutonomousGoalType.BEHAVIOR_OPTIMIZATION,
                    "优化高频行为：" + stats.skillName(),
                    "将高频且高成功率的行为固化为更高效的流程：" + stats.skillName(),
                    "llm.orchestrate",
                    stats.skillName(),
                    priority,
                    params,
                    reasons,
                    Instant.now()
            ));
        }
        return goals;
    }

    private List<AutonomousGoal> generateMemoryReviewGoals(String userId) {
        List<ConversationTurn> history = dispatcherMemoryFacade.recentHistory(userId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int effectiveWindow = Math.min(recentHistoryTurns, history.size());
        List<ConversationTurn> recent = history.subList(history.size() - effectiveWindow, history.size());
        String recentSummary = summarizeRecentHistory(recent);
        int priority = 35 + Math.min(15, recent.size() * 2);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recentTurnCount", recent.size());
        params.put("recentSummary", recentSummary);
        params.put(
                "recentTurns",
                recent.stream()
                        .map(turn -> Map.of(
                                "role", turn.role(),
                                "content", truncate(turn.content(), 120)
                        ))
                        .toList()
        );
        List<String> reasons = new ArrayList<>();
        reasons.add("recentTurns=" + recent.size());
        reasons.add("conversationSummary=" + truncate(recentSummary, 160));
        return List.of(new AutonomousGoal(
                "memory:review",
                AutonomousGoalType.MEMORY_REVIEW,
                "整理最近对话记忆",
                "总结最近对话并提炼下一步行动",
                "llm.orchestrate",
                "recent-history",
                priority,
                params,
                reasons,
                Instant.now()
        ));
    }

    private AutonomousGoal fallbackGoal(String userId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reason", "fallback");
        params.put("userId", userId);
        return new AutonomousGoal(
                "fallback:llm.orchestrate",
                AutonomousGoalType.FALLBACK,
                "保持自治循环",
                "回顾当前记忆并生成下一步可执行目标",
                "llm.orchestrate",
                "fallback",
                10,
                params,
                List.of("no actionable history"),
                Instant.now()
        );
    }

    private int scoreLongTask(LongTask task, Instant now) {
        int score = switch (task.status()) {
            case BLOCKED -> 95;
            case RUNNING -> 85;
            case PENDING -> 75;
            case COMPLETED, CANCELLED -> 0;
        };
        score += clampTimeBonus(task.dueAt(), now, 72, 20);
        score += clampTimeBonus(task.nextCheckAt(), now, 12, 12);
        score += Math.max(0, 18 - task.progressPercent() / 6);
        return Math.max(0, Math.min(100, score));
    }

    private int clampTimeBonus(Instant deadline, Instant now, int hoursWindow, int maxBonus) {
        if (deadline == null || now == null) {
            return 0;
        }
        long hours = Duration.between(now, deadline).toHours();
        if (hours <= 0L) {
            return maxBonus;
        }
        if (hours >= hoursWindow) {
            return 0;
        }
        double ratio = 1.0 - hours / (double) hoursWindow;
        return (int) Math.round(maxBonus * ratio);
    }

    private String summarizeRecentHistory(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record AutonomousGoal(String goalId,
                             AutonomousGoalType type,
                             String title,
                             String objective,
                             String target,
                             String sourceId,
                             int priority,
                             Map<String, Object> params,
                             List<String> reasons,
                             Instant generatedAt) {

    public AutonomousGoal {
        goalId = normalize(goalId);
        type = type == null ? AutonomousGoalType.FALLBACK : type;
        title = firstNonBlank(title, objective, "自治目标");
        objective = firstNonBlank(objective, title, "自动生成自治目标");
        target = firstNonBlank(target, "llm.orchestrate");
        sourceId = normalize(sourceId);
        priority = Math.max(0, Math.min(100, priority));
        params = params == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(params));
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public Decision toDecision() {
        Map<String, Object> decisionParams = new LinkedHashMap<>(params);
        decisionParams.put("autonomousGoalId", goalId);
        decisionParams.put("autonomousGoalType", type.name());
        decisionParams.put("autonomousGoalTitle", title);
        decisionParams.put("autonomousGoalObjective", objective);
        decisionParams.put("autonomousGoalPriority", priority);
        if (!sourceId.isBlank()) {
            decisionParams.put("autonomousGoalSourceId", sourceId);
        }
        return new Decision(
                objective.isBlank() ? title : objective,
                target,
                decisionParams,
                confidence(),
                false
        );
    }

    public double confidence() {
        return clamp(0.55 + priority / 200.0);
    }

    public Goal toGoal() {
        return Goal.from(this);
    }

    public String summary() {
        return "goal=" + goalId
                + ",type=" + type
                + ",priority=" + priority
                + ",target=" + target
                + ",source=" + sourceId
                + ",objective=" + objective;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private static String firstNonBlank(String... values) {
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

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

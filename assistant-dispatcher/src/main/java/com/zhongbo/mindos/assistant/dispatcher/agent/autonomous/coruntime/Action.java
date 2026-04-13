package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Action(String actionId,
                     String taskId,
                     String goalId,
                     String summary,
                     double confidence,
                     double risk,
                     double cost,
                     List<String> targets,
                     Map<String, Object> metadata) {

    public Action {
        actionId = actionId == null ? "" : actionId.trim();
        taskId = taskId == null ? "" : taskId.trim();
        goalId = goalId == null ? "" : goalId.trim();
        summary = summary == null ? "" : summary.trim();
        confidence = clamp(confidence, 0.0);
        risk = clamp(risk, 0.0);
        cost = clamp(cost, 0.0);
        targets = targets == null ? List.of() : List.copyOf(targets);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

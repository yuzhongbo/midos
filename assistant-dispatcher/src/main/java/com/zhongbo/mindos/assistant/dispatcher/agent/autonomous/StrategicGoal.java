package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record StrategicGoal(String goal,
                            double priority,
                            List<String> actions,
                            List<String> reasons,
                            Instant generatedAt) {

    public StrategicGoal {
        goal = normalize(goal);
        priority = clamp(priority);
        actions = sanitize(actions);
        reasons = sanitize(reasons);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public String summary() {
        return "goal=" + goal
                + ",priority=" + round(priority)
                + ",actions=" + String.join(" | ", actions)
                + ",reasons=" + String.join(" | ", reasons);
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

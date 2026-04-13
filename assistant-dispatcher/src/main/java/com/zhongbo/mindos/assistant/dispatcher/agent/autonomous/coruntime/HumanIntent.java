package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record HumanIntent(String userId,
                          String goal,
                          double autonomyPreference,
                          double riskTolerance,
                          double costSensitivity,
                          String decisionStyle,
                          String notes,
                          Map<String, Object> attributes,
                          Instant capturedAt) {

    public HumanIntent {
        userId = userId == null ? "" : userId.trim();
        goal = goal == null ? "" : goal.trim();
        autonomyPreference = clamp(autonomyPreference, 0.6);
        riskTolerance = clamp(riskTolerance, 0.5);
        costSensitivity = clamp(costSensitivity, 0.5);
        decisionStyle = decisionStyle == null || decisionStyle.isBlank() ? "balanced" : decisionStyle.trim();
        notes = notes == null ? "" : notes.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    public static HumanIntent empty() {
        return new HumanIntent("", "", 0.6, 0.5, 0.5, "balanced", "", Map.of(), Instant.now());
    }

    public boolean present() {
        return !userId.isBlank()
                || !goal.isBlank()
                || !notes.isBlank()
                || !attributes.isEmpty();
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

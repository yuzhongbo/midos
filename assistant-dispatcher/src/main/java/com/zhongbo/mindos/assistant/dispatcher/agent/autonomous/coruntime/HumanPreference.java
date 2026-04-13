package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record HumanPreference(double autonomyLevel,
                              double riskTolerance,
                              double costSensitivity,
                              String decisionStyle,
                              String language,
                              String preferredChannel,
                              boolean prefersExplanations,
                              Instant updatedAt) {

    public HumanPreference {
        autonomyLevel = clamp(autonomyLevel, 0.6);
        riskTolerance = clamp(riskTolerance, 0.5);
        costSensitivity = clamp(costSensitivity, 0.5);
        decisionStyle = decisionStyle == null || decisionStyle.isBlank() ? "balanced" : decisionStyle.trim();
        language = language == null ? "" : language.trim();
        preferredChannel = preferredChannel == null ? "" : preferredChannel.trim();
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static HumanPreference defaultPreference() {
        return new HumanPreference(0.6, 0.5, 0.5, "balanced", "", "", true, Instant.now());
    }

    public Map<String, Object> asMap() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("autonomyLevel", autonomyLevel);
        values.put("riskTolerance", riskTolerance);
        values.put("costSensitivity", costSensitivity);
        values.put("decisionStyle", decisionStyle);
        values.put("language", language);
        values.put("preferredChannel", preferredChannel);
        values.put("prefersExplanations", prefersExplanations);
        values.put("updatedAt", updatedAt.toString());
        return Map.copyOf(values);
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

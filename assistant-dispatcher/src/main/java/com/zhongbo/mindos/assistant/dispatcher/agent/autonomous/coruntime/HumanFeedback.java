package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record HumanFeedback(String userId,
                            String goalId,
                            String taskId,
                            double satisfactionScore,
                            boolean approvedOutcome,
                            boolean requestRollback,
                            boolean requestInterrupt,
                            Double suggestedAutonomy,
                            Double suggestedRiskTolerance,
                            Double suggestedCostSensitivity,
                            String preferredStyle,
                            String language,
                            String preferredChannel,
                            String notes,
                            Map<String, Object> corrections,
                            Instant providedAt) {

    public HumanFeedback {
        userId = userId == null ? "" : userId.trim();
        goalId = goalId == null ? "" : goalId.trim();
        taskId = taskId == null ? "" : taskId.trim();
        satisfactionScore = clamp(satisfactionScore, 0.5);
        suggestedAutonomy = clampNullable(suggestedAutonomy);
        suggestedRiskTolerance = clampNullable(suggestedRiskTolerance);
        suggestedCostSensitivity = clampNullable(suggestedCostSensitivity);
        preferredStyle = preferredStyle == null ? "" : preferredStyle.trim();
        language = language == null ? "" : language.trim();
        preferredChannel = preferredChannel == null ? "" : preferredChannel.trim();
        notes = notes == null ? "" : notes.trim();
        corrections = corrections == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(corrections));
        providedAt = providedAt == null ? Instant.now() : providedAt;
    }

    public static HumanFeedback empty() {
        return new HumanFeedback(
                "",
                "",
                "",
                0.5,
                true,
                false,
                false,
                null,
                null,
                null,
                "",
                "",
                "",
                "",
                Map.of(),
                Instant.now()
        );
    }

    public boolean present() {
        return !userId.isBlank()
                || !goalId.isBlank()
                || !taskId.isBlank()
                || Math.abs(satisfactionScore - 0.5) > 0.0001
                || !approvedOutcome
                || requestRollback
                || requestInterrupt
                || suggestedAutonomy != null
                || suggestedRiskTolerance != null
                || suggestedCostSensitivity != null
                || !preferredStyle.isBlank()
                || !language.isBlank()
                || !preferredChannel.isBlank()
                || !notes.isBlank()
                || !corrections.isEmpty();
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Double clampNullable(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

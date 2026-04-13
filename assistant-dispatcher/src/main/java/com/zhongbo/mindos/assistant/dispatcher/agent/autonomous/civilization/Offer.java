package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.util.LinkedHashMap;
import java.util.Map;

public record Offer(String orgId,
                    double capabilityScore,
                    double predictedSuccess,
                    double quotedCost,
                    double expectedLatency,
                    double reputationScore,
                    Map<ResourceType, Double> resourceCommitment,
                    String summary) {

    public Offer {
        orgId = orgId == null ? "" : orgId.trim();
        capabilityScore = clamp(capabilityScore);
        predictedSuccess = clamp(predictedSuccess);
        quotedCost = Math.max(0.0, quotedCost);
        expectedLatency = clamp(expectedLatency);
        reputationScore = clamp(reputationScore);
        resourceCommitment = resourceCommitment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceCommitment));
        summary = summary == null ? "" : summary.trim();
    }

    public double marketScore() {
        double costEfficiency = clamp(1.0 - Math.min(1.0, quotedCost / 100.0));
        return clamp(predictedSuccess * 0.40
                + capabilityScore * 0.25
                + reputationScore * 0.20
                + costEfficiency * 0.10
                + (1.0 - expectedLatency) * 0.05);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

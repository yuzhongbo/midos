package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

public record KPI(double successRate,
                  double costEfficiency,
                  double latency,
                  double goalCompletionRate) {

    public KPI {
        successRate = clamp(successRate);
        costEfficiency = clamp(costEfficiency);
        latency = clamp(latency);
        goalCompletionRate = clamp(goalCompletionRate);
    }

    public static KPI empty() {
        return new KPI(0.0, 0.0, 1.0, 0.0);
    }

    public double healthScore() {
        return clamp(successRate * 0.35
                + costEfficiency * 0.25
                + (1.0 - latency) * 0.20
                + goalCompletionRate * 0.20);
    }

    public boolean needsAttention() {
        return healthScore() < 0.55;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

public record PlanScore(double score,
                        double efficiencyScore) {

    public PlanScore {
        score = clamp(score);
        efficiencyScore = clamp(efficiencyScore);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

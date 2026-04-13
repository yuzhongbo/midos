package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

public record PredictionResult(double successProbability,
                               double cost,
                               double latency,
                               double risk) {

    public PredictionResult {
        successProbability = clamp(successProbability);
        cost = clamp(cost);
        latency = clamp(latency);
        risk = clamp(risk);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

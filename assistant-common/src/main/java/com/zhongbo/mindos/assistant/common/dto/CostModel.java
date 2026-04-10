package com.zhongbo.mindos.assistant.common.dto;

public record CostModel(double tokenCost, double latency, double successRate) {

    public CostModel {
        tokenCost = normalize(tokenCost);
        latency = normalize(latency);
        successRate = normalize(successRate);
    }

    public double cost() {
        return normalize((tokenCost + latency) / 2.0);
    }

    public static CostModel neutral() {
        return new CostModel(0.5, 0.5, 0.5);
    }

    private static double normalize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

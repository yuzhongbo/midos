package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record RewardModel(String skillName,
                          String routeType,
                          boolean success,
                          boolean highLatency,
                          long latencyMs,
                          int tokenEstimate,
                          boolean usedFallback,
                          double reward,
                          List<String> reasons) {

    public RewardModel {
        skillName = normalize(skillName);
        routeType = normalize(routeType);
        latencyMs = Math.max(0L, latencyMs);
        tokenEstimate = Math.max(0, tokenEstimate);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static RewardModel evaluate(String skillName,
                                       String routeType,
                                       boolean success,
                                       long latencyMs,
                                       int tokenEstimate,
                                       boolean usedFallback,
                                       long highLatencyThresholdMs) {
        long safeThreshold = Math.max(1L, highLatencyThresholdMs);
        boolean highLatency = latencyMs >= safeThreshold;
        double reward = success ? 1.0 : -1.0;
        List<String> reasons = new ArrayList<>();
        reasons.add(success ? "success:+1.0" : "failure:-1.0");
        if (highLatency) {
            reward -= 0.5;
            reasons.add("high-latency:-0.5");
        }
        if (usedFallback) {
            reasons.add("fallback-used");
        }
        return new RewardModel(skillName, routeType, success, highLatency, latencyMs, tokenEstimate, usedFallback, reward, reasons);
    }

    public double normalizedReward() {
        return clamp(0.5 + reward / 3.0);
    }

    public String summary() {
        return "skill=" + skillName
                + ",route=" + routeType
                + ",reward=" + round(reward)
                + ",normalized=" + round(normalizedReward())
                + ",success=" + success
                + ",highLatency=" + highLatency
                + ",latencyMs=" + latencyMs
                + ",tokenEstimate=" + tokenEstimate
                + ",usedFallback=" + usedFallback
                + ",reasons=" + reasons;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.isBlank() ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

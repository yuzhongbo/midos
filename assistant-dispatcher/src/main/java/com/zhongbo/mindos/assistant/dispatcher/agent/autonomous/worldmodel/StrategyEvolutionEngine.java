package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StrategyEvolutionEngine {

    private final Map<String, StrategyProfile> profiles = new ConcurrentHashMap<>();

    public void update(List<WorldMemory.ExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        Map<String, List<WorldMemory.ExecutionTrace>> byAgent = traces.stream()
                .filter(trace -> trace != null && trace.agentId() != null && !trace.agentId().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(WorldMemory.ExecutionTrace::agentId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        byAgent.forEach((agentId, agentTraces) -> profiles.put(agentId, profileOf(agentId, agentTraces)));
    }

    public StrategyProfile profile(String agentId) {
        return profiles.getOrDefault(agentId, StrategyProfile.neutral(agentId));
    }

    public double weightOf(String agentId) {
        return profile(agentId).weight();
    }

    public Map<String, StrategyProfile> profiles() {
        return Map.copyOf(profiles);
    }

    private StrategyProfile profileOf(String agentId, List<WorldMemory.ExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return StrategyProfile.neutral(agentId);
        }
        double successRate = traces.stream().filter(WorldMemory.ExecutionTrace::success).count() / (double) traces.size();
        double predictionAccuracy = traces.stream()
                .mapToDouble(trace -> 1.0 - Math.min(1.0, Math.abs(trace.errorGap())))
                .average()
                .orElse(0.5);
        double efficiency = traces.stream()
                .mapToDouble(trace -> {
                    PredictionResult prediction = trace.prediction();
                    if (prediction == null) {
                        return 0.5;
                    }
                    return clamp(1.0 - (prediction.cost() * 0.6 + prediction.latency() * 0.4));
                })
                .average()
                .orElse(0.5);
        double weight = clamp(successRate * 0.4 + efficiency * 0.3 + predictionAccuracy * 0.3);
        return new StrategyProfile(agentId, successRate, efficiency, predictionAccuracy, weight, traces.size());
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record StrategyProfile(String agentId,
                                  double successRate,
                                  double costEfficiency,
                                  double predictionAccuracy,
                                  double weight,
                                  int sampleCount) {

        public StrategyProfile {
            agentId = agentId == null ? "" : agentId.trim();
            successRate = clamp(successRate);
            costEfficiency = clamp(costEfficiency);
            predictionAccuracy = clamp(predictionAccuracy);
            weight = clamp(weight);
            sampleCount = Math.max(0, sampleCount);
        }

        public static StrategyProfile neutral(String agentId) {
            return new StrategyProfile(agentId, 0.5, 0.5, 0.5, 0.5, 0);
        }
    }
}

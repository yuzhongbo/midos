package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record CapabilityProfile(List<String> domains,
                                List<String> plannerStrategies,
                                double planningStrength,
                                double executionStrength,
                                double reliability,
                                double costMultiplier,
                                double latencyMultiplier,
                                Map<ResourceType, Double> pricing) {

    public CapabilityProfile {
        domains = domains == null ? List.of() : List.copyOf(domains.stream()
                .filter(domain -> domain != null && !domain.isBlank())
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
        plannerStrategies = plannerStrategies == null ? List.of() : List.copyOf(plannerStrategies.stream()
                .filter(strategy -> strategy != null && !strategy.isBlank())
                .map(strategy -> strategy.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
        planningStrength = clamp(planningStrength);
        executionStrength = clamp(executionStrength);
        reliability = clamp(reliability);
        costMultiplier = clampPositive(costMultiplier, 1.0);
        latencyMultiplier = clampPositive(latencyMultiplier, 1.0);
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    public double matchScore(TaskRequest request) {
        if (request == null) {
            return 0.0;
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>(request.focusAreas());
        targets.addAll(request.goalKeywords());
        if (targets.isEmpty()) {
            return clamp((planningStrength + executionStrength + reliability) / 3.0);
        }
        long matched = targets.stream()
                .filter(target -> domains.contains(target))
                .count();
        double domainMatch = matched / (double) targets.size();
        return clamp(domainMatch * 0.45
                + planningStrength * 0.20
                + executionStrength * 0.20
                + reliability * 0.15);
    }

    public double quoteCost(Map<ResourceType, Double> requiredResources) {
        if (requiredResources == null || requiredResources.isEmpty()) {
            return 0.0;
        }
        double raw = 0.0;
        for (Map.Entry<ResourceType, Double> entry : requiredResources.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            double amount = clampPositive(entry.getValue() == null ? 0.0 : entry.getValue(), 0.0);
            double unitPrice = pricing.getOrDefault(entry.getKey(), 1.0);
            raw += amount * unitPrice;
        }
        return raw * costMultiplier;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampPositive(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            return fallback;
        }
        return value;
    }
}

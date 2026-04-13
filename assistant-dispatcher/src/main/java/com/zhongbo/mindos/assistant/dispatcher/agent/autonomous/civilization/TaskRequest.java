package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record TaskRequest(String requesterOrgId,
                          Goal goal,
                          List<String> focusAreas,
                          Map<ResourceType, Double> requiredResources,
                          double maxCost,
                          double priority,
                          boolean requiresApproval,
                          Map<String, Object> metadata) {

    public TaskRequest {
        requesterOrgId = requesterOrgId == null ? "" : requesterOrgId.trim();
        focusAreas = focusAreas == null ? List.of() : List.copyOf(focusAreas.stream()
                .filter(area -> area != null && !area.isBlank())
                .map(area -> area.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
        requiredResources = requiredResources == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(requiredResources));
        maxCost = clampPositive(maxCost);
        priority = clamp(priority);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public List<String> goalKeywords() {
        if (goal == null || goal.description().isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String part : goal.description().toLowerCase(Locale.ROOT).split("[\\s,，。;；]+")) {
            if (!part.isBlank()) {
                keywords.add(part.trim());
            }
            if (keywords.size() >= 6) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampPositive(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}

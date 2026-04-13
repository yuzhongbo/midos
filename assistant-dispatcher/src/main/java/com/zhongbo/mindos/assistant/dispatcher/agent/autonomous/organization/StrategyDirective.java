package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record StrategyDirective(Goal goal,
                                String strategicTheme,
                                String planningMode,
                                Map<String, Integer> resourceAllocation,
                                List<String> focusAreas,
                                Map<String, Object> profileContext) {

    public StrategyDirective {
        strategicTheme = strategicTheme == null ? "" : strategicTheme.trim();
        planningMode = planningMode == null || planningMode.isBlank() ? "balanced" : planningMode.trim().toLowerCase(java.util.Locale.ROOT);
        resourceAllocation = resourceAllocation == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceAllocation));
        focusAreas = focusAreas == null ? List.of() : List.copyOf(focusAreas);
        profileContext = profileContext == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(profileContext));
    }
}

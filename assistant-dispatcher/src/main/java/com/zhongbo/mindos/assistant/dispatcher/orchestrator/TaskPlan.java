package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TaskPlan(List<TaskStep> steps) {

    public static TaskPlan from(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new TaskPlan(List.of());
        }
        Object raw = params.get("steps");
        if (!(raw instanceof List<?> values)) {
            return new TaskPlan(List.of());
        }
        List<TaskStep> steps = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            String id = stringValue(normalized.get("id"));
            String target = stringValue(normalized.get("target"));
            if (target.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> stepParams = normalized.get("params") instanceof Map<?, ?> rawParams
                    ? ((Map<?, ?>) rawParams).entrySet().stream()
                    .collect(LinkedHashMap::new,
                            (acc, entry) -> acc.put(String.valueOf(entry.getKey()), entry.getValue()),
                            LinkedHashMap::putAll)
                    : Map.of();
            String saveAs = stringValue(normalized.get("saveAs"));
            boolean optional = Boolean.parseBoolean(String.valueOf(normalized.getOrDefault("optional", false)));
            steps.add(new TaskStep(id, target, stepParams, saveAs, optional));
        }
        return new TaskPlan(steps);
    }

    public TaskPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

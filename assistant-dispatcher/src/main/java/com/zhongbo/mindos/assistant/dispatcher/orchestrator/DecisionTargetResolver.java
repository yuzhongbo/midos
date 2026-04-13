package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DecisionTargetResolver {

    private static final Map<String, String> LEGACY_INTENT_TARGETS = Map.of(
            "learning", "teaching.plan",
            "eq", "eq.coach",
            "task", "todo.create",
            "coding", "code.generate"
    );

    private static final Set<String> SIMPLE_TARGETS = Set.of("echo", "time");

    public String canonicalize(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        String normalized = normalize(rawValue);
        if (normalized.isBlank()) {
            return "";
        }
        String mapped = LEGACY_INTENT_TARGETS.get(normalized);
        if (mapped != null) {
            return mapped;
        }
        if (isSkillLikeTarget(normalized)) {
            return trimmed;
        }
        return "";
    }

    public boolean hasCanonicalTarget(String rawValue) {
        return !canonicalize(rawValue).isBlank();
    }

    private boolean isSkillLikeTarget(String normalized) {
        return normalized.contains(".")
                || normalized.contains("_")
                || SIMPLE_TARGETS.contains(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

package com.zhongbo.mindos.assistant.skill.learning;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record ToolGenerationRequest(String userId,
                                    String request,
                                    String skillNameHint,
                                    Map<String, Object> hints) {

    public ToolGenerationRequest {
        userId = normalize(userId);
        request = normalize(request);
        skillNameHint = normalize(skillNameHint);
        hints = normalizeHints(hints);
    }

    public Optional<Object> hintValue(String key) {
        if (key == null || key.isBlank() || hints.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(hints.get(key));
    }

    public String hintText(String key) {
        return hintValue(key)
                .map(String::valueOf)
                .map(String::trim)
                .orElse("");
    }

    public String normalizedRequest() {
        return request.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> normalizeHints(Map<String, Object> rawHints) {
        if (rawHints == null || rawHints.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawHints.forEach((key, value) -> {
            if (key == null || String.valueOf(key).isBlank() || value == null) {
                return;
            }
            normalized.put(String.valueOf(key), value);
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

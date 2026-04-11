package com.zhongbo.mindos.assistant.skill.learning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolGenerationResult(String skillName,
                                   String packageName,
                                   String className,
                                   ToolGenerationKind kind,
                                   String description,
                                   List<String> routingKeywords,
                                   String sourceCode,
                                   String rationale,
                                   Map<String, Object> metadata) {

    public ToolGenerationResult {
        skillName = normalize(skillName);
        packageName = normalize(packageName);
        className = normalize(className);
        kind = kind == null ? ToolGenerationKind.TEMPLATE : kind;
        description = normalize(description);
        routingKeywords = normalizeKeywords(routingKeywords);
        sourceCode = sourceCode == null ? "" : sourceCode;
        rationale = normalize(rationale);
        metadata = normalizeMetadata(metadata);
    }

    public String fullyQualifiedClassName() {
        if (packageName.isBlank()) {
            return className;
        }
        return packageName + "." + className;
    }

    private static List<String> normalizeKeywords(List<String> rawKeywords) {
        if (rawKeywords == null || rawKeywords.isEmpty()) {
            return List.of();
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String keyword : rawKeywords) {
            String normalized = normalize(keyword);
            if (!normalized.isBlank()) {
                ordered.putIfAbsent(normalized, normalized);
            }
        }
        return ordered.isEmpty() ? List.of() : List.copyOf(ordered.values());
    }

    private static Map<String, Object> normalizeMetadata(Map<String, Object> rawMetadata) {
        if (rawMetadata == null || rawMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawMetadata.forEach((key, value) -> {
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

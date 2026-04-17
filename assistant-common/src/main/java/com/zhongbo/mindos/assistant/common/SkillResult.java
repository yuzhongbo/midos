package com.zhongbo.mindos.assistant.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record SkillResult(String skillName,
                          String output,
                          boolean success,
                          Map<String, Object> artifacts,
                          Map<String, Object> metadata) {

    public SkillResult(String skillName, String output, boolean success) {
        this(skillName, output, success, Map.of(), Map.of());
    }

    public SkillResult {
        skillName = skillName == null ? "" : skillName.trim();
        output = output == null ? "" : output;
        artifacts = immutableCopy(artifacts);
        metadata = immutableCopy(metadata);
    }

    public static SkillResult success(String skillName, String output) {
        return new SkillResult(skillName, output, true);
    }

    public static SkillResult failure(String skillName, String output) {
        return new SkillResult(skillName, output, false);
    }

    public SkillResult relabel(String newSkillName) {
        String normalized = newSkillName == null ? "" : newSkillName.trim();
        if (normalized.equals(skillName)) {
            return this;
        }
        return new SkillResult(normalized, output, success, artifacts, metadata);
    }

    public SkillResult withArtifact(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return this;
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(artifacts);
        merged.put(key.trim(), value);
        return new SkillResult(skillName, output, success, merged, metadata);
    }

    public SkillResult withArtifacts(Map<String, Object> additions) {
        Map<String, Object> merged = mergeMaps(artifacts, additions);
        if (merged.equals(artifacts)) {
            return this;
        }
        return new SkillResult(skillName, output, success, merged, metadata);
    }

    public SkillResult withMetadata(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return this;
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key.trim(), value);
        return new SkillResult(skillName, output, success, artifacts, merged);
    }

    public SkillResult withMetadata(Map<String, Object> additions) {
        Map<String, Object> merged = mergeMaps(metadata, additions);
        if (merged.equals(metadata)) {
            return this;
        }
        return new SkillResult(skillName, output, success, artifacts, merged);
    }

    public Optional<Object> artifact(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(artifacts.get(key.trim()));
    }

    public Optional<Object> metadata(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(metadata.get(key.trim()));
    }

    public String artifactText(String key) {
        return artifact(key).map(String::valueOf).map(String::trim).orElse("");
    }

    public String metadataText(String key) {
        return metadata(key).map(String::valueOf).map(String::trim).orElse("");
    }

    private static Map<String, Object> mergeMaps(Map<String, Object> current, Map<String, Object> additions) {
        if (additions == null || additions.isEmpty()) {
            return current == null ? Map.of() : current;
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        additions.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                merged.put(key.trim(), value);
            }
        });
        return immutableCopy(merged);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                copy.put(key.trim(), value);
            }
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}

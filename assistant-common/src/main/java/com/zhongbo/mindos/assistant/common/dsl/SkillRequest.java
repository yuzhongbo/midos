package com.zhongbo.mindos.assistant.common.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extended request envelope that supports multiple skill calls
 * plus optional request-level metadata and context.
 */
public record SkillRequest(
        List<SkillDSL> skills,
        Map<String, Object> metadata,
        Map<String, Object> context
) {

    public SkillRequest {
        skills = skills == null ? List.of() : List.copyOf(skills);
        metadata = immutableMap(metadata);
        context = immutableMap(context);
    }

    public static SkillRequest of(List<SkillDSL> skills) {
        return new SkillRequest(skills, Map.of(), Map.of());
    }

    private static Map<String, Object> immutableMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}


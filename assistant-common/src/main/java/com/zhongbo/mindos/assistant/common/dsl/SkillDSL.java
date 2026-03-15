package com.zhongbo.mindos.assistant.common.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extended Skill DSL node that can represent a single skill call
 * and optionally include nested downstream skill calls.
 */
public record SkillDSL(
        String skill,
        Map<String, Object> input,
        List<SkillDSL> nestedSkills,
        Map<String, Object> metadata,
        Map<String, Object> context
) {

    public SkillDSL {
        input = immutableMap(input);
        nestedSkills = nestedSkills == null ? List.of() : List.copyOf(nestedSkills);
        metadata = immutableMap(metadata);
        context = immutableMap(context);
    }

    public static SkillDSL of(String skill, Map<String, Object> input) {
        return new SkillDSL(skill, input, List.of(), Map.of(), Map.of());
    }

    private static Map<String, Object> immutableMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}


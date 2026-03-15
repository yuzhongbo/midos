package com.zhongbo.mindos.assistant.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SkillDsl(String skill, Map<String, Object> input) {

    public SkillDsl {
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    public static SkillDsl of(String skill) {
        return new SkillDsl(skill, Map.of());
    }

    // Compatibility accessors for existing code paths.
    public String skillName() {
        return skill;
    }

    public Map<String, String> arguments() {
        Map<String, String> asStringMap = new LinkedHashMap<>();
        input.forEach((key, value) -> asStringMap.put(key, String.valueOf(value)));
        return Collections.unmodifiableMap(asStringMap);
    }
}

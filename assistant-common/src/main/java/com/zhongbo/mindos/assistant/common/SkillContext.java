package com.zhongbo.mindos.assistant.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SkillContext(String userId, String input, Map<String, Object> attributes) {

    public SkillContext {
        Map<String, Object> safeAttributes = attributes == null ? Map.of() : attributes;
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(safeAttributes));
    }

    public static SkillContext of(String userId, String input) {
        return new SkillContext(userId, input, Map.of());
    }
}

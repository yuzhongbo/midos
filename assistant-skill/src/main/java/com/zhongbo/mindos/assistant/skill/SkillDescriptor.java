package com.zhongbo.mindos.assistant.skill;

import java.util.List;

public record SkillDescriptor(String name, String description, List<String> routingKeywords) {

    public SkillDescriptor {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        routingKeywords = routingKeywords == null ? List.of() : List.copyOf(routingKeywords);
    }
}

package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.List;

public interface Skill {
    String name();

    String description();

    SkillResult run(SkillContext context);

    // Optional keyword metadata for centralized routing and semantic analysis.
    default List<String> routingKeywords() {
        return List.of();
    }

    // Optional hook for natural-language auto-routing; DSL execution does not rely on this.
    default boolean supports(String input) {
        return false;
    }

    // Optional score hook for natural-language routing. Higher scores win.
    default int routingScore(String input) {
        return supports(input) ? 1 : Integer.MIN_VALUE;
    }
}

package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

public interface Skill {
    String name();

    String description();

    SkillResult run(SkillContext context);

    // Optional hook for natural-language auto-routing; DSL execution does not rely on this.
    default boolean supports(String input) {
        return false;
    }
}

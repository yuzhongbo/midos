package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

public interface Skill {
    String name();

    String description();

    SkillResult run(SkillContext context);
}

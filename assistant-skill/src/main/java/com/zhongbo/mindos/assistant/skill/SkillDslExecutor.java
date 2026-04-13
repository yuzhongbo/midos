package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.springframework.stereotype.Component;

@Component
public class SkillDslExecutor {

    private final SkillRegistry skillRegistry;

    public SkillDslExecutor(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillResult execute(SkillDsl dsl, SkillContext context) {
        return skillRegistry.get(dsl.skill())
                .map(skill -> skill.run(context))
                .orElseGet(() -> SkillResult.failure("dsl", "Unknown skill: " + dsl.skill()));
    }
}

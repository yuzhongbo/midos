package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

@Component
public class CodeGenerateSkill implements Skill {

    @Override
    public String name() {
        return "code.generate";
    }

    @Override
    public String description() {
        return "Generates code from a task description (skeleton placeholder).";
    }

    @Override
    public SkillResult run(SkillContext context) {
        String taskDescription = asString(context, "task", context.input());
        String output = "[code.generate] Placeholder generated code for task: " + taskDescription;
        return SkillResult.success(name(), output);
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}


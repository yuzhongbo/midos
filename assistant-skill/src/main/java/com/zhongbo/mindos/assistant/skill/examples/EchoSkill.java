package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

@Component
public class EchoSkill implements Skill {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "Echoes back the text after the 'echo' command.";
    }

    @Override
    public boolean supports(String input) {
        return input != null && input.toLowerCase().startsWith("echo ");
    }

    @Override
    public SkillResult run(SkillContext context) {
        if (context.input() == null || context.input().length() <= "echo ".length()) {
            return SkillResult.failure(name(), "Usage: echo <text>");
        }
        String output = context.input().substring("echo ".length());
        return SkillResult.success(name(), output);
    }
}

package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

@Component
public class TimeSkill implements Skill {

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "Returns the current server time.";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.toLowerCase();
        return normalized.contains("time") || normalized.contains("clock");
    }

    @Override
    public SkillResult run(SkillContext context) {
        return SkillResult.success(name(), "Current time is " + ZonedDateTime.now());
    }
}

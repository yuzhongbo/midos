package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

@Component
public class TodoCreateSkill implements Skill {

    @Override
    public String name() {
        return "todo.create";
    }

    @Override
    public String description() {
        return "Creates a todo item from task description and due date (skeleton placeholder).";
    }

    @Override
    public SkillResult run(SkillContext context) {
        String task = asString(context, "task", "Untitled task");
        String dueDate = asString(context, "dueDate", "unspecified");

        String output = "[todo.create] Placeholder: todo '" + task + "' scheduled for " + dueDate + ".";
        return SkillResult.success(name(), output);
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}


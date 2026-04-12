package com.zhongbo.mindos.assistant.skill.loader;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

final class TemplateScriptSkill extends ScriptSkill {

    TemplateScriptSkill(ScriptSkillDefinition definition) {
        super(definition);
    }

    @Override
    public SkillResult run(SkillContext context) {
        String output = definition.response()
                .replace("{{input}}", resolvedInput(context))
                .replace("{{user}}", resolvedUser(context));
        return SkillResult.success(name(), output);
    }
}

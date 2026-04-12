package com.zhongbo.mindos.assistant.skill.loader;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;

abstract class ScriptSkill implements Skill, SkillDescriptorProvider {

    protected final ScriptSkillDefinition definition;

    ScriptSkill(ScriptSkillDefinition definition) {
        this.definition = definition;
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), definition.triggers());
    }

    protected String resolvedInput(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return "";
        }
        Object input = context.attributes().get("input");
        if (input == null) {
            return "";
        }
        String normalized = String.valueOf(input).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    protected String resolvedUser(SkillContext context) {
        return context == null || context.userId() == null ? "" : context.userId();
    }
}

package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class McpDelegatingSkill implements Skill, SkillDescriptorProvider {

    private final McpToolDefinition definition;
    private final McpToolExecutor executor;

    McpDelegatingSkill(McpToolDefinition definition, McpToolExecutor executor) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return definition.skillName();
    }

    @Override
    public String description() {
        return definition.description() == null ? "" : definition.description();
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of(
                definition.serverAlias(),
                definition.name(),
                splitCamelCase(definition.name()),
                description()
        ));
    }

    @Override
    public SkillResult run(SkillContext context) {
        Map<String, Object> params = new LinkedHashMap<>(context == null || context.attributes() == null
                ? Map.of()
                : context.attributes());
        if (context != null) {
            if (context.userId() != null && !context.userId().isBlank()) {
                params.putIfAbsent("userId", context.userId());
            }
            if (context.input() != null && !context.input().isBlank()) {
                params.putIfAbsent("input", context.input());
                params.putIfAbsent("originalInput", context.input());
            }
        }
        return executor.execute(name(), params);
    }

    private String splitCamelCase(String value) {
        return value == null ? "" : value.replaceAll("([a-z])([A-Z])", "$1 $2");
    }
}

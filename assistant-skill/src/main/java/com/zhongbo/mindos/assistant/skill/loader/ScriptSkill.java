package com.zhongbo.mindos.assistant.skill.loader;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;

import java.util.List;
import java.util.Map;

/**
 * A {@link Skill} backed by a {@link ScriptSkillDefinition} (loaded from a JSON file).
 *
 * Response behaviour:
 *   "llm"      — routes input to the LlmClient
 *   otherwise  — static template; {{input}} and {{user}} are expanded
 */
public class ScriptSkill implements Skill, SkillDescriptorProvider {

    private final ScriptSkillDefinition definition;
    private final LlmClient llmClient;

    public ScriptSkill(ScriptSkillDefinition definition, LlmClient llmClient) {
        this.definition = definition;
        this.llmClient = llmClient;
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

    @Override
    public SkillResult run(SkillContext context) {
        String resp = definition.response();
        String input = resolvedInput(context);

        if ("llm".equalsIgnoreCase(resp)) {
            if (llmClient == null) {
                return SkillResult.failure(name(), "No LLM client configured for script skill: " + name());
            }
            String output = llmClient.generateResponse(
                    input,
                    Map.of("userId", context.userId() == null ? "" : context.userId())
            );
            return SkillResult.success(name(), output);
        }

        // Template expansion
        String user = context.userId() == null ? "" : context.userId();
        String output = resp
                .replace("{{input}}", input)
                .replace("{{user}}", user);
        return SkillResult.success(name(), output);
    }

    private String resolvedInput(SkillContext context) {
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
}

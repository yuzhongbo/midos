package com.zhongbo.mindos.assistant.skill.loader;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;

import java.util.Map;

/**
 * A {@link Skill} backed by a {@link ScriptSkillDefinition} (loaded from a JSON file).
 *
 * Response behaviour:
 *   "llm"      — routes input to the LlmClient
 *   otherwise  — static template; {{input}} and {{user}} are expanded
 */
public class ScriptSkill implements Skill {

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
    public boolean supports(String input) {
        if (input == null || definition.triggers().isEmpty()) {
            return false;
        }
        String normalized = input.toLowerCase();
        return definition.triggers().stream()
                .anyMatch(t -> normalized.contains(t.toLowerCase()));
    }

    @Override
    public SkillResult run(SkillContext context) {
        String resp = definition.response();

        if ("llm".equalsIgnoreCase(resp)) {
            if (llmClient == null) {
                return SkillResult.failure(name(), "No LLM client configured for script skill: " + name());
            }
            String output = llmClient.generateResponse(
                    context.input(),
                    Map.of("userId", context.userId() == null ? "" : context.userId())
            );
            return SkillResult.success(name(), output);
        }

        // Template expansion
        String input = context.input() == null ? "" : context.input();
        String user = context.userId() == null ? "" : context.userId();
        String output = resp
                .replace("{{input}}", input)
                .replace("{{user}}", user);
        return SkillResult.success(name(), output);
    }
}


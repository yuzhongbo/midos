package com.zhongbo.mindos.assistant.skill.loader;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.Map;

final class LlmScriptSkill extends ScriptSkill {

    private final LlmClient llmClient;

    LlmScriptSkill(ScriptSkillDefinition definition, LlmClient llmClient) {
        super(definition);
        this.llmClient = llmClient;
    }

    @Override
    public SkillResult run(SkillContext context) {
        if (llmClient == null) {
            return SkillResult.failure(name(), "No LLM client configured for script skill: " + name());
        }
        String output = llmClient.generateResponse(
                resolvedInput(context),
                Map.of("userId", resolvedUser(context))
        );
        return SkillResult.success(name(), output);
    }
}

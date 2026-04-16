package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmOrchestrateSkill implements Skill, SkillDescriptorProvider {

    private final LlmOrchestrateExecutor executor;

    @Autowired
    public LlmOrchestrateSkill(LlmClient llmClient,
                               @Value("${mindos.llm.orchestrate.providers:openai,deepseek,qwen}") String providers,
                               @Value("${mindos.llm.orchestrate.max-hops:2}") int maxHops,
                               @Value("${mindos.llm.orchestrate.prompt.max-chars:1600}") int promptMaxChars,
                               @Value("${mindos.llm.orchestrate.history.max-items:6}") int historyMaxItems) {
        this(new LlmOrchestrateExecutor(
                llmClient,
                LlmOrchestrateExecutor.parseProviders(providers),
                promptMaxChars,
                historyMaxItems
        ));
    }

    // Test helper constructor
    LlmOrchestrateSkill(LlmClient llmClient,
                        List<String> providers,
                        int maxHops,
                        int promptMaxChars,
                        int historyMaxItems) {
        this(new LlmOrchestrateExecutor(llmClient, providers, promptMaxChars, historyMaxItems));
    }

    LlmOrchestrateSkill(LlmOrchestrateExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "llm.orchestrate";
    }

    @Override
    public String description() {
        return "Builds a single LLM prompt from recent context and forwards it to the selected provider.";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("llm.orchestrate", "调用模型", "llm prompt", "模型总结", "模型回复"));
    }

    @Override
    public SkillResult run(SkillContext context) {
        return executor.execute(name(), context);
    }
}

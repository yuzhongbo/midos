package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeGenerateSkillTest {

    @Test
    void shouldUseDefaultProviderAndEasyModelForSimpleTask() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        CodeGenerateSkill skill = new CodeGenerateSkill(
                llmClient,
                "gpt",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2"
        );

        SkillResult result = skill.run(new SkillContext("u1", "写一个hello world函数", Map.of("task", "写一个hello world函数")));

        assertEquals("llm-output", result.output());
        assertEquals("gpt", llmClient.lastContext.get("llmProvider"));
        assertEquals("openai/gpt-5-mini", llmClient.lastContext.get("model"));
    }

    @Test
    void shouldUseHardModelForComplexTask() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        CodeGenerateSkill skill = new CodeGenerateSkill(
                llmClient,
                "gpt",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2"
        );

        String hardTask = "请设计一个支持并发写入和事务回滚的订单服务，包含接口层、service层、repository层、异常处理和测试用例，顺便给出SQL索引优化建议";
        SkillResult result = skill.run(new SkillContext("u2", hardTask, Map.of("task", hardTask)));

        assertEquals("llm-output", result.output());
        assertEquals("openai/gpt-5.2", llmClient.lastContext.get("model"));
    }

    private static class CapturingLlmClient implements LlmClient {
        private Map<String, Object> lastContext = Map.of();

        @Override
        public String generateResponse(String prompt, Map<String, Object> context) {
            this.lastContext = new LinkedHashMap<>(context == null ? Map.of() : context);
            return "llm-output";
        }
    }
}

package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAnalyzeSkillTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRenderHumanReadableSemanticSummary() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("code.generate")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service);

        String output = skill.run(new SkillContext("u1", "请帮我修复 Spring 接口 bug", Map.of())).output();

        assertTrue(output.contains("[semantic.analyze]"));
        assertTrue(output.contains("候选意图:"));
        assertTrue(output.contains("code.generate"));
        assertTrue(output.contains("意图: 生成或整理代码实现方案"));
    }

    @Test
    void shouldRenderDispatchContractJsonShape() throws Exception {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service);

        String output = skill.run(new SkillContext(
                "u1",
                "帮我创建待办，明天提醒",
                Map.of("responseFormat", "json", "memoryContext", "最近用户连续创建待办并关注截止日期")
        )).output();

        Map<String, Object> json = objectMapper.readValue(output, new TypeReference<>() {
        });
        assertTrue(json.containsKey("intent"));
        assertTrue(json.containsKey("payloadHints"));
        assertTrue(json.containsKey("contextSummary"));
        assertTrue(json.containsKey("candidateIntents"));
        assertEquals("创建待办或提醒事项", String.valueOf(json.get("intent")));
    }

    @Test
    void shouldIgnoreLegacyTargetInputAndUseCanonicalInput() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service);

        String output = skill.run(new SkillContext("u1", "查询天气", Map.of("targetInput", "帮我创建待办"))).output();

        assertTrue(output.contains("原始输入: 查询天气"));
        assertTrue(output.contains("天气"));
    }

    private record FixedSkill(String name) implements Skill {
        @Override
        public String description() {
            return name;
        }

        @Override
        public com.zhongbo.mindos.assistant.common.SkillResult run(SkillContext context) {
            return com.zhongbo.mindos.assistant.common.SkillResult.success(name, name);
        }
    }
}

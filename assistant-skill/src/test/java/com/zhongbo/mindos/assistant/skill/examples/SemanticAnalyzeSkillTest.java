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
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service, registry);

        String output = skill.run(new SkillContext("u1", "请帮我修复 Spring 接口 bug", Map.of())).output();

        assertTrue(output.contains("[semantic.analyze]"));
        assertTrue(output.contains("建议技能: code.generate"));
        assertTrue(output.contains("意图: 生成或整理代码实现方案"));
    }

    @Test
    void shouldRenderDispatchContractJsonShape() throws Exception {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service, registry);

        String output = skill.run(new SkillContext(
                "u1",
                "帮我创建待办，明天提醒",
                Map.of("responseFormat", "json", "memoryContext", "最近用户连续创建待办并关注截止日期")
        )).output();

        Map<String, Object> json = objectMapper.readValue(output, new TypeReference<>() {
        });
        assertTrue(json.containsKey("intent"));
        assertTrue(json.containsKey("params"));
        assertTrue(json.containsKey("priority"));
        assertTrue(json.containsKey("context_summary"));
        assertTrue(json.containsKey("cloud_enhance_needed"));
        assertTrue(json.containsKey("candidate_intents"));
        assertEquals("todo.create", String.valueOf(json.get("intent")));
    }

    @Test
    void shouldRejectMismatchedActorWithoutApprovalInJsonMode() throws Exception {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service, registry);

        String output = skill.run(new SkillContext(
                "u-owner",
                "帮我创建待办",
                Map.of("responseFormat", "json", "actorId", "u-guest")
        )).output();

        Map<String, Object> json = objectMapper.readValue(output, new TypeReference<>() {
        });
        assertEquals("access_denied", String.valueOf(json.get("intent")));
        assertEquals(0, ((Number) json.get("priority")).intValue());
        assertTrue(json.containsKey("candidate_intents"));
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

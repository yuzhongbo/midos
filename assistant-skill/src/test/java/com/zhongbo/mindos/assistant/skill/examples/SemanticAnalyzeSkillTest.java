package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAnalyzeSkillTest {

    @Test
    void shouldRenderHumanReadableSemanticSummary() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("code.generate")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, "");
        SemanticAnalyzeSkill skill = new SemanticAnalyzeSkill(service);

        String output = skill.run(new SkillContext("u1", "请帮我修复 Spring 接口 bug", Map.of())).output();

        assertTrue(output.contains("[semantic.analyze]"));
        assertTrue(output.contains("建议技能: code.generate"));
        assertTrue(output.contains("意图: 生成或整理代码实现方案"));
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

package com.zhongbo.mindos.assistant.skill.semantic;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAnalysisServiceTest {

    @Test
    void shouldInferLocalSkillWithHeuristics() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create"),
                new FixedSkill("code.generate")
        ));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, "");

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我创建一个待办，截止周五前提交周报",
                "",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("todo.create", result.suggestedSkill());
        assertTrue(result.confidence() >= 0.8);
        assertEquals("帮我创建一个待办，截止周五前提交周报", result.payload().get("task"));
        assertEquals("周五前提交周报", result.payload().get("dueDate"));
    }

    @Test
    void shouldAcceptDelegateSkillOutputLikeMcpRouter() {
        Skill delegateSkill = new Skill() {
            @Override
            public String name() {
                return "mcp.semantic.route";
            }

            @Override
            public String description() {
                return "Delegated semantic routing";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(),
                        "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建一个待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.93}");
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(delegateSkill, new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, "mcp.semantic.route");

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我安排一下周报这件事",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("skill:mcp.semantic.route", result.source());
        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
        assertTrue(result.confidence() > 0.9);
    }

    private record FixedSkill(String name) implements Skill {
        @Override
        public String description() {
            return name;
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, name);
        }
    }
}

package com.zhongbo.mindos.assistant.skill.semantic;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.SkillRoutingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAnalysisServiceTest {

    @Test
    void shouldInferLocalSkillWithHeuristics() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create"),
                new FixedSkill("code.generate")
        ));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

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
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "mcp.semantic.route", "local", "cost", 120);

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

    @Test
    void shouldUseLlmAnalysisWhenHeuristicsAreLowConfidence() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        LlmClient llmClient = (prompt, context) ->
                "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.91}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我处理一下周报这件事",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("llm", result.source());
        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
        assertTrue(result.confidence() >= 0.9);
    }

    @Test
    void shouldForceLocalProviderContextForSemanticLlmAnalysis() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicReference<Map<String, Object>> capturedContext = new AtomicReference<>();
        LlmClient llmClient = (prompt, context) -> {
            capturedContext.set(context);
            return "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.91}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        service.analyze(
                "u1",
                "请帮我处理一下周报安排",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("semantic-analysis", capturedContext.get().get("routeStage"));
        assertEquals("local", capturedContext.get().get("llmProvider"));
        assertEquals("cost", capturedContext.get().get("llmPreset"));
        assertEquals(120, capturedContext.get().get("maxTokens"));
    }

    @Test
    void shouldForceLocalProviderContextEvenWhenCloudProviderIsConfigured() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicReference<Map<String, Object>> capturedContext = new AtomicReference<>();
        LlmClient llmClient = (prompt, context) -> {
            capturedContext.set(context);
            return "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.91}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "qwen", "quality", 120);

        service.analyze(
                "u1",
                "请帮我处理一下周报安排",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("semantic-analysis", capturedContext.get().get("routeStage"));
        assertEquals("local", capturedContext.get().get("llmProvider"));
        assertEquals("quality", capturedContext.get().get("llmPreset"));
        assertEquals(120, capturedContext.get().get("maxTokens"));
    }

    @Test
    void shouldUseConfiguredProviderWhenForceLocalIsDisabled() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicReference<Map<String, Object>> capturedContext = new AtomicReference<>();
        LlmClient llmClient = (prompt, context) -> {
            capturedContext.set(context);
            return "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.91}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, false, "", "qwen", "quality", 120);

        service.analyze(
                "u1",
                "请帮我处理一下周报安排",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("semantic-analysis", capturedContext.get().get("routeStage"));
        assertEquals("qwen", capturedContext.get().get("llmProvider"));
        assertEquals("quality", capturedContext.get().get("llmPreset"));
        assertEquals(120, capturedContext.get().get("maxTokens"));
    }

    @Test
    void shouldUseConfiguredRoutingKeywordsDuringHeuristicAnalysis() {
        SkillRoutingProperties properties = new SkillRoutingProperties();
        properties.getKeywords().put("teaching.plan", "冲刺路线,路线规划");
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("teaching.plan")), properties);
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我做一个 Java 冲刺路线",
                "",
                Map.of(),
                List.of("teaching.plan - plan study path")
        );

        assertEquals("teaching.plan", result.suggestedSkill());
        assertTrue(result.confidence() >= 0.8);
        assertTrue(result.keywords().stream().anyMatch(keyword -> keyword.contains("冲刺路线")));
    }

    @Test
    void shouldEscalateSemanticAnalysisToCloudWhenLocalConfidenceIsLow() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicInteger calls = new AtomicInteger();
        LlmClient llmClient = (prompt, context) -> {
            int idx = calls.incrementAndGet();
            if (idx == 1) {
                return "{\"intent\":\"待办\",\"rewrittenInput\":\"先本地分析\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"本地\"},\"keywords\":[\"待办\"],\"confidence\":0.42}";
            }
            return "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.92}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(
                llmClient,
                registry,
                true,
                true,
                true,
                "",
                "local",
                "cost",
                120,
                true,
                "qwen",
                "quality",
                0.78
        );

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我处理一下周报这件事",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals(2, calls.get());
        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
        assertTrue(result.confidence() >= 0.9);
    }

    @Test
    void shouldSkipCloudEscalationWhenLocalSemanticConfidenceIsHigh() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicInteger calls = new AtomicInteger();
        LlmClient llmClient = (prompt, context) -> {
            calls.incrementAndGet();
            return "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.89}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(
                llmClient,
                registry,
                true,
                true,
                true,
                "",
                "local",
                "cost",
                120,
                true,
                "qwen",
                "quality",
                0.78
        );

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我处理一下周报这件事",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals(1, calls.get());
        assertEquals("todo.create", result.suggestedSkill());
        assertTrue(result.confidence() >= 0.89);
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

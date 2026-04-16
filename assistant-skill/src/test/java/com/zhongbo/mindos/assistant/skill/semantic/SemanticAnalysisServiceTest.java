package com.zhongbo.mindos.assistant.skill.semantic;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.SkillRoutingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void shouldInferWeatherRealtimeSkillWithHeuristics() {
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", realtimeRegistry(), true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我看看成都今天的天气和空气质量",
                "",
                Map.of(),
                List.of("mcp.bravesearch.webSearch - search latest web info")
        );

        assertEquals("mcp.bravesearch.webSearch", result.suggestedSkill());
        assertEquals("weather", result.payload().get("domain"));
        assertTrue(result.keywords().contains("天气"));
        assertTrue(result.confidence() >= 0.88);
    }

    @Test
    void shouldInferNewsRealtimeSkillWithHeuristics() {
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", realtimeRegistry(), true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我看今天有哪些最新新闻",
                "",
                Map.of(),
                List.of("news_search - news summary")
        );

        assertEquals("news_search", result.suggestedSkill());
        assertEquals("news", result.payload().get("domain"));
        assertTrue(result.keywords().contains("新闻"));
        assertTrue(result.confidence() >= 0.89);
    }

    @Test
    void shouldInferMarketRealtimeSkillWithHeuristics() {
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", realtimeRegistry(), true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我查一下今天的A股行情",
                "",
                Map.of(),
                List.of("mcp.bravesearch.webSearch - search latest web info")
        );

        assertEquals("mcp.bravesearch.webSearch", result.suggestedSkill());
        assertEquals("market", result.payload().get("domain"));
        assertTrue(result.keywords().contains("行情"));
        assertTrue(result.confidence() >= 0.87);
    }

    @Test
    void shouldInferTravelRealtimeSkillWithHeuristics() {
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", realtimeRegistry(), true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我看看上海到北京的高铁出行情况",
                "",
                Map.of(),
                List.of("mcp.bravesearch.webSearch - search latest web info")
        );

        assertEquals("mcp.bravesearch.webSearch", result.suggestedSkill());
        assertEquals("travel", result.payload().get("domain"));
        assertTrue(result.keywords().contains("高铁"));
        assertTrue(result.confidence() >= 0.86);
    }

    @Test
    void shouldIgnoreDelegateSkillExecutionPath() {
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

        assertEquals("heuristic", result.source());
        assertNotEquals("skill:mcp.semantic.route", result.source());
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
    void shouldPassConfiguredSemanticModelIntoLlmContext() {
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
                Map.of("llmModel", "phi3:mini"),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("semantic-analysis", capturedContext.get().get("routeStage"));
        // Profile-provided model should be preserved in semantic call context.
        assertEquals("phi3:mini", capturedContext.get().get("model"));
    }

    @Test
    void shouldUseConfiguredRoutingKeywordsDuringHeuristicAnalysis() {
        SkillRoutingProperties properties = new SkillRoutingProperties();
        properties.getKeywords().put("teaching.plan", "冲刺路线,路线规划");
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("teaching.plan")), properties);
        SemanticAnalysisService service = new SemanticAnalysisService(
                (prompt, context) -> "stub",
                registry,
                new DefaultSkillCatalog(registry, null, properties),
                true,
                false,
                true,
                "",
                "local",
                "cost",
                120
        );

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
        List<Map<String, Object>> contexts = new ArrayList<>();
        LlmClient llmClient = (prompt, context) -> {
            int idx = calls.incrementAndGet();
            contexts.add(context);
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
        assertEquals("local", String.valueOf(contexts.get(0).get("llmProvider")));
        assertEquals("qwen", String.valueOf(contexts.get(1).get("llmProvider")));
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

    @Test
    void shouldSkipSemanticLlmForShortSimpleInput() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicInteger calls = new AtomicInteger();
        LlmClient llmClient = (prompt, context) -> {
            calls.incrementAndGet();
            return "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\"],\"confidence\":0.92}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "收到",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals(0, calls.get());
        assertNotEquals("llm", result.source());
    }

    @Test
    void shouldUseSemanticLlmForShortInputWhenTriggerTermMatches() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        AtomicInteger calls = new AtomicInteger();
        LlmClient llmClient = (prompt, context) -> {
            calls.incrementAndGet();
            return "{\"intent\":\"新闻查询\",\"rewrittenInput\":\"查询新闻\",\"suggestedSkill\":\"\",\"payload\":{},\"keywords\":[\"新闻\"],\"confidence\":0.90}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "查新闻",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals(1, calls.get());
        assertEquals("llm", result.source());
        assertTrue(result.confidence() >= 0.9);
    }

    @Test
    void shouldBuildProductionDecisionPromptWithMemoryAndToolSchemas() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create"),
                new FixedSkill("mcp.docs.searchDocs"),
                new FixedSkill("mcp.bravesearch.webSearch")
        ));
        AtomicReference<String> capturedPrompt = new AtomicReference<>("");
        LlmClient llmClient = (prompt, context) -> {
            capturedPrompt.set(prompt);
            return "{\"intent\":\"docs_lookup\",\"target\":\"mcp.docs.searchDocs\",\"params\":{\"query\":\"Spring Boot RestClient 官方文档\"},\"confidence\":0.93}";
        };
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我查 Spring Boot RestClient 官方文档",
                "Recent conversation:\n- user: 继续看文档\nUser skill habits:\n- mcp.docs.searchDocs",
                Map.of(
                        "imPlatform", "dingtalk",
                        "searchPriorityOrder", List.of("mcp.docs.searchDocs", "mcp.bravesearch.webSearch")
                ),
                List.of(
                        "mcp.docs.searchDocs - Search official documentation | required=query | keywords=docs/manual/guide",
                        "mcp.bravesearch.webSearch - Search latest web info | required=query | keywords=search/news/weather"
                )
        );

        assertEquals("mcp.docs.searchDocs", result.suggestedSkill());
        assertEquals("Spring Boot RestClient 官方文档", result.payload().get("query"));
        assertTrue(capturedPrompt.get().contains("Return strict JSON only"));
        assertTrue(capturedPrompt.get().contains("CONFIRMED_MEMORY_AND_CONTEXT:"));
        assertTrue(capturedPrompt.get().contains("AVAILABLE_TOOLS:"));
        assertTrue(capturedPrompt.get().contains("SEARCH_PRIORITY_ORDER:"));
        assertTrue(capturedPrompt.get().contains("BASELINE_ROUTING_HINT:"));
        assertTrue(capturedPrompt.get().contains("Only use skill names listed in AVAILABLE_TOOLS."));
        assertTrue(capturedPrompt.get().contains("mcp.docs.searchDocs - Search official documentation"));
    }

    @Test
    void shouldExposeSemanticSummaryForHeuristicResult() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我创建一个待办，截止明天提交周报",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertFalse(result.summary().isBlank());
        assertTrue(String.valueOf(result.asAttributes().get("semanticSummary")).contains("待办"));
    }

    @Test
    void shouldInheritTaskAndSkillFromMemoryContextForExecutionFollowUp() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "开始吧",
                """
                Relevant knowledge:
                - [意图摘要] 用户当前想要：提交周报；可用执行方式：todo.create；已确认信息：task=提交周报, dueDate=周五前
                """,
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
        assertEquals("周五前", result.payload().get("dueDate"));
        assertEquals("continuation", result.contextScope());
        assertEquals("continue", result.intentState());
        assertEquals("提交周报", result.taskFocus());
    }

    @Test
    void shouldCarryContextForGenericContinuationWithoutForcingSkill() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "继续",
                """
                Relevant knowledge:
                - [任务事实] 当前事项：整理季度复盘；截止时间：周四下午
                """,
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertTrue(result.suggestedSkill().isBlank());
        assertEquals("continuation", result.contextScope());
        assertEquals("continue", result.intentState());
        assertEquals("整理季度复盘", result.taskFocus());
        assertTrue(result.rewrittenInput().contains("整理季度复盘"));
    }

    @Test
    void shouldCarryPauseIntentForActiveTaskFromMemoryContext() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "先这样，暂停这个",
                """
                Active task thread:
                - 当前事项：提交周报
                - 状态：进行中
                - 下一步：同步项目风险
                """,
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("pause", result.intentState());
        assertEquals("提交周报", result.taskFocus());
        assertTrue(result.rewrittenInput().contains("暂停当前事项"));
    }

    @Test
    void shouldCarryUpdateConstraintFromActiveTaskThread() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "改成周三发",
                """
                Active task thread:
                - 当前事项：提交周报
                - 状态：进行中
                - 下一步：同步项目风险
                """,
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("continuation", result.contextScope());
        assertEquals("update", result.intentState());
        assertEquals("decision", result.intentPhase());
        assertEquals("continue", result.threadRelation());
        assertEquals("提交周报", result.taskFocus());
        assertTrue(result.rewrittenInput().contains("当前事项：提交周报"));
    }

    @Test
    void shouldCarryBlockingIntentFromActiveTaskThread() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "卡住了，接口一直报错",
                """
                Active task thread:
                - 当前事项：提交周报
                - 状态：进行中
                - 下一步：同步项目风险
                """,
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("blocked", result.intentState());
        assertEquals("blocking", result.intentPhase());
        assertEquals("提交周报", result.taskFocus());
        assertTrue(result.rewrittenInput().contains("当前事项遇到阻塞"));
    }

    @Test
    void shouldCarryPlanningIntentForCurrentTaskWithoutForcingSkill() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        SemanticAnalysisService service = new SemanticAnalysisService((prompt, context) -> "stub", registry, true, false, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "先给我一个方案",
                """
                Active task thread:
                - 当前事项：提交周报
                - 状态：进行中
                - 下一步：同步项目风险
                """,
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertTrue(result.suggestedSkill().isBlank());
        assertEquals("planning", result.intentPhase());
        assertEquals("continue", result.threadRelation());
        assertEquals("提交周报", result.taskFocus());
        assertTrue(result.rewrittenInput().contains("为当前事项制定下一步方案"));
    }

    @Test
    void shouldParseParamsAliasIntoPayload() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        LlmClient llmClient = (prompt, context) ->
                "{\"intent\":\"创建待办\",\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"params\":{\"task\":\"提交周报\"},\"keywords\":[\"待办\",\"周报\"],\"confidence\":0.91}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "查新闻",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("llm", result.source());
        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
    }

    @Test
    void shouldParseTargetAliasAndCandidateIntentsCamelCase() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        LlmClient llmClient = (prompt, context) ->
                "{\"intent\":\"task_create\",\"target\":\"todo.create\",\"params\":{\"task\":\"提交周报\"},\"candidateIntents\":[{\"intent\":\"todo.create\",\"confidence\":0.88}],\"confidence\":0.88}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "帮我创建一个待办：提交周报",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
        assertEquals(1, result.candidateIntents().size());
        assertEquals("todo.create", result.candidateIntents().get(0).intent());
    }

    @Test
    void shouldBackfillIntentAndSummaryWhenLlmFieldsAreMissing() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        LlmClient llmClient = (prompt, context) ->
                "{\"rewrittenInput\":\"请创建待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"confidence\":0.82}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "请根据这段描述做语义结构化分析并输出路由建议",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("todo.create", result.suggestedSkill());
        assertEquals("提交周报", result.payload().get("task"));
        assertFalse(result.intent().isBlank());
        assertFalse(result.summary().isBlank());
    }

    @Test
    void shouldHandleMalformedLlmOutputGracefully() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        // Malformed / adversarial LLM output: wrong types, extra keys
        LlmClient llmClient = (prompt, context) ->
                "{\"intent\":null,\"rewrittenInput\":123,\"suggestedSkill\":\"todo.create\",\"payload\":\"notamap\",\"keywords\":\"待办,周报\",\"confidence\":\"notanumber\",\"extra\":\"evil\"}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "处理周报",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        // Should not throw and should produce predictable, cleaned fields.
        // Because payload was not a map, the service will avoid suggesting a skill to prevent accidental execution.
        assertTrue(result.suggestedSkill().isBlank());
        // payload was not a map -> cleaned to empty
        assertEquals(0, result.payload().size());
        // keywords string should be split into tokens
        assertFalse(result.keywords().isEmpty());
        // malformed confidence leads to a low-confidence LLM result; service should fall back to heuristics
        assertTrue(result.confidence() >= 0.35);
        // rewrittenInput numeric value should be stringified/backfilled into summary/intent if needed
        assertFalse(result.summary().isBlank());
    }

    @Test
    void shouldIgnoreNonObjectLlmOutputAndFallbackToHeuristics() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        LlmClient llmClient = (prompt, context) -> "[\"todo.create\", \"payload\"]";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "请帮我创建待办事项并安排到周五",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertNotEquals("llm", result.source());
        assertEquals("todo.create", result.suggestedSkill());
    }

    @Test
    void shouldParseCandidateIntentsAndExposeSuggestedSkillConfidence() {
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")));
        LlmClient llmClient = (prompt, context) ->
                "{\"intent\":\"创建待办\",\"rewrittenInput\":\"帮我弄个待办\",\"suggestedSkill\":\"todo.create\",\"payload\":{\"task\":\"提交周报\"},\"confidence\":0.45,\"candidate_intents\":[{\"intent\":\"todo.create\",\"confidence\":0.84},{\"intent\":\"eq.coach\",\"confidence\":0.31}]}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "请把这段需求做结构化意图分析并路由到合适技能：提交周报",
                "history",
                Map.of(),
                List.of("todo.create - Creates todo items")
        );

        assertEquals("todo.create", result.suggestedSkill());
        assertEquals(2, result.candidateIntents().size());
        assertEquals(0.84, result.confidenceForSkill("todo.create"));
        assertTrue(result.effectiveConfidence() >= 0.84);
    }

    @Test
    void shouldDropInternalSemanticAnalyzeSuggestionFromLlmOutput() {
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("semantic.analyze"),
                new FixedSkill("mcp.bravesearch.webSearch")
        ));
        LlmClient llmClient = (prompt, context) ->
                "{\"intent\":\"weather_query\",\"rewrittenInput\":\"查询成都今天的天气\",\"suggestedSkill\":\"semantic.analyze\",\"payload\":{\"location\":\"成都\"},\"confidence\":0.52,\"candidate_intents\":[{\"intent\":\"semantic.analyze\",\"confidence\":0.95},{\"intent\":\"mcp.bravesearch.webSearch\",\"confidence\":0.88}]}";
        SemanticAnalysisService service = new SemanticAnalysisService(llmClient, registry, true, true, true, "", "local", "cost", 120);

        SemanticAnalysisResult result = service.analyze(
                "u1",
                "请帮我查询今天成都天气并给出结构化路由",
                "history",
                Map.of(),
                List.of("semantic.analyze - internal semantic analyzer", "mcp.bravesearch.webSearch - search latest web info")
        );

        assertTrue(result.suggestedSkill().isBlank());
        assertEquals(1, result.candidateIntents().size());
        assertEquals("mcp.bravesearch.webSearch", result.candidateIntents().get(0).intent());
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

    private static SkillRegistry realtimeRegistry() {
        return new SkillRegistry(List.of(
                new FixedSkill("mcp.bravesearch.webSearch"),
                new FixedSkill("news_search")
        ));
    }
}

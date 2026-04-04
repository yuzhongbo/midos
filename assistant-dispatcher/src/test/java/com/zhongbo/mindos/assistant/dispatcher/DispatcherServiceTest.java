package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.memory.CentralMemoryRepository;
import com.zhongbo.mindos.assistant.memory.EpisodicMemoryService;
import com.zhongbo.mindos.assistant.memory.InMemoryCentralMemoryRepository;
import com.zhongbo.mindos.assistant.memory.LongTaskService;
import com.zhongbo.mindos.assistant.memory.MemoryCompressionPlanningService;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.MemorySyncService;
import com.zhongbo.mindos.assistant.memory.PreferenceProfileService;
import com.zhongbo.mindos.assistant.memory.ProceduralMemoryService;
import com.zhongbo.mindos.assistant.memory.SemanticMemoryService;
import com.zhongbo.mindos.assistant.memory.SemanticWriteGatePolicy;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.common.dto.LocalEscalationMetricsDto;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.mcp.McpJsonRpcClient;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolDefinition;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolSkill;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherServiceTest {

    @Test
    void shouldRouteRealtimeNewsToPreferredQwenMcpSearchSkill() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "qwen result"),
                newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "brave result")
        ), 2);

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("mcp.qwensearch.webSearch", result.channel());
        assertEquals("qwen result", result.reply());
        assertEquals("detected-skill", result.executionTrace().routing().route());
        assertEquals("mcp.qwensearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldFallbackToBraveMcpSearchSkillWhenQwenIsUnavailable() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "brave result")
        ), 2);

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("mcp.bravesearch.webSearch", result.channel());
        assertEquals("brave result", result.reply());
        assertEquals("detected-skill", result.executionTrace().routing().route());
        assertEquals("mcp.bravesearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldPreferBraveSearchWhenBraveFirstRoutingIsEnabled() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "qwen result"),
                newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "brave result")
        ), 2, true, true);

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("mcp.bravesearch.webSearch", result.channel());
        assertEquals("brave result", result.reply());
        assertEquals("brave-first-search", result.executionTrace().routing().route());
        assertEquals("mcp.bravesearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldFinalizeMcpSearchOutputWithWildcardAllowlist() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("这是整理后的新闻摘要"));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "raw search result: AI 芯片与机器人动态")),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "",
                "",
                false,
                "",
                true,
                "mcp.*",
                "",
                ""
        );

        DispatchResult result = service.dispatch("news-user", "查看今天新闻 科技");

        assertEquals("mcp.qwensearch.webSearch", result.channel());
        assertTrue(result.reply().startsWith("今日新闻标题："));
        assertTrue(result.reply().contains("1. 这是整理后的新闻摘要"));
        assertTrue(result.reply().contains("2. raw search result: AI 芯片与机器人动态"));
        assertTrue(result.reply().contains("总结：这是整理后的新闻摘要"));
        assertEquals(1, llmClient.finalizeContexts().size());
        assertEquals("skill-postprocess", llmClient.finalizeContexts().get(0).get("routeStage"));
    }

    @Test
    void shouldLogWhichMcpSourceWasSentToLlmPostprocess() {
        Logger logger = Logger.getLogger(DispatcherService.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            MemoryManager qwenMemoryManager = createMemoryManager();
            RecordingLlmClient qwenLlmClient = new RecordingLlmClient(List.of("qwen summary"));
            DispatcherService qwenService = createDispatcher(
                    qwenMemoryManager,
                    qwenLlmClient,
                    List.of(newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "raw qwen result")),
                    2,
                    "auto",
                    0,
                    "time",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    true,
                    "mcp.*",
                    "",
                    ""
            );
            qwenService.dispatch("news-user", "查看今天新闻 科技");

            MemoryManager braveMemoryManager = createMemoryManager();
            RecordingLlmClient braveLlmClient = new RecordingLlmClient(List.of("brave summary"));
            DispatcherService braveService = createDispatcher(
                    braveMemoryManager,
                    braveLlmClient,
                    List.of(newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "raw brave result")),
                    2,
                    "auto",
                    0,
                    "time",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    true,
                    "mcp.*",
                    "",
                    ""
            );
            braveService.dispatch("news-user", "查看今天新闻 科技");

            String logs = String.join("\n", handler.messages());
            assertTrue(logs.contains("dispatcher.skill-postprocess.trace"));
            assertTrue(logs.contains("\"source\":\"qwen\""));
            assertTrue(logs.contains("\"channel\":\"mcp.qwensearch.webSearch\""));
            assertTrue(logs.contains("\"source\":\"brave\""));
            assertTrue(logs.contains("\"channel\":\"mcp.bravesearch.webSearch\""));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldEmitFinalAggregateTraceForQwenSuccessAndBraveFallback() {
        Logger logger = Logger.getLogger(DispatcherService.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            MemoryManager qwenMemoryManager = createMemoryManager();
            RecordingLlmClient qwenLlmClient = new RecordingLlmClient(List.of("整理后的 qwen 摘要"));
            DispatcherService qwenService = createDispatcher(
                    qwenMemoryManager,
                    qwenLlmClient,
                    List.of(newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "raw qwen result")),
                    2,
                    "auto",
                    0,
                    "time",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    true,
                    "mcp.*",
                    "",
                    ""
            );
            qwenService.dispatch("news-user", "查看今天新闻 科技");

            MemoryManager braveMemoryManager = createMemoryManager();
            RecordingLlmClient braveLlmClient = new RecordingLlmClient(List.of("fallback llm reply"));
            DispatcherService braveService = createDispatcher(
                    braveMemoryManager,
                    braveLlmClient,
                    List.of(new Skill() {
                        @Override
                        public String name() {
                            return "mcp.bravesearch.webSearch";
                        }

                        @Override
                        public String description() {
                            return "Brave latest web news search";
                        }

                        @Override
                        public SkillResult run(SkillContext context) {
                            return SkillResult.failure(name(), "Brave timeout");
                        }

                        @Override
                        public boolean supports(String input) {
                            return input != null && input.contains("新闻");
                        }

                        @Override
                        public int routingScore(String input) {
                            return supports(input) ? 900 : Integer.MIN_VALUE;
                        }
                    }),
                    2,
                    "auto",
                    0,
                    "time",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    true,
                    "mcp.*",
                    "",
                    ""
            );
            braveService.dispatch("news-user", "查看今天新闻 科技");

            String logs = String.join("\n", handler.messages());
            assertTrue(logs.contains("dispatcher.final.trace"), logs);
            assertTrue(logs.contains("\"searchSource\":\"qwen\""), logs);
            assertTrue(logs.contains("\"searchStatus\":\"success\""), logs);
            assertTrue(logs.contains("\"selectedSkill\":\"mcp.qwensearch.webSearch\""), logs);
            assertTrue(logs.contains("\"postprocessSent\":true"), logs);
            assertTrue(logs.contains("\"finalChannel\":\"mcp.qwensearch.webSearch\""), logs);
            assertTrue(logs.contains("\"searchSource\":\"brave\""), logs);
            assertTrue(logs.contains("\"searchStatus\":\"failed\""), logs);
            assertTrue(logs.contains("\"selectedSkill\":\"mcp.bravesearch.webSearch\""), logs);
            assertTrue(logs.contains("\"fallbackUsed\":false"), logs);
            assertTrue(logs.contains("\"finalChannel\":\"mcp.bravesearch.webSearch\""), logs);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldUseNewsSpecificFinalizePromptForRealtimeMcpSearch() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("整理后的新闻简报"));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "1. AI 芯片\n2. 机器人\n3. 云计算")),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "",
                "",
                false,
                "",
                true,
                "mcp.*",
                "",
                ""
        );

        service.dispatch("news-user", "查看今天新闻 科技");

        String finalizePrompt = llmClient.fallbackPrompts().stream()
                .filter(prompt -> prompt.startsWith("你是新闻整理助手。"))
                .findFirst()
                .orElseThrow();
        assertTrue(finalizePrompt.contains("今日新闻标题："));
        assertTrue(finalizePrompt.contains("1. ..."));
        assertTrue(finalizePrompt.contains("总结：..."));
        assertTrue(finalizePrompt.contains("严格按下面结构输出"));
    }

    @Test
    void shouldNormalizeDriftedNewsFinalizeOutputIntoStableBriefShape() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "今天科技方向主要聚焦 AI 芯片、机器人和云计算，整体看行业还在持续推进，没有特别突兀的单点变化。"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "1. AI 芯片进展\n2. 机器人新品\n3. 云计算平台升级\nhttps://example.com/news-1")),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "",
                "",
                false,
                "",
                true,
                "mcp.*",
                "",
                ""
        );

        DispatchResult result = service.dispatch("news-user", "查看今天新闻 科技");

        assertEquals("mcp.qwensearch.webSearch", result.channel());
        assertTrue(result.reply().startsWith("今日新闻标题："));
        assertTrue(result.reply().contains("1. AI 芯片进展"));
        assertTrue(result.reply().contains("2. 机器人新品"));
        assertTrue(result.reply().contains("3. 云计算平台升级"));
        assertTrue(result.reply().contains("总结："));
    }

    @Test
    void shouldKeepGenericFinalizePromptForNonNewsMcpSkill() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("整理后的文档答复"));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(newMcpSkill("mcp.docs.searchDocs", "Search product documentation", "Auth guide result")),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "",
                "",
                false,
                "",
                true,
                "mcp.*",
                "",
                ""
        );

        service.dispatch("docs-user", "search docs for auth guide");

        String finalizePrompt = llmClient.fallbackPrompts().stream()
                .filter(prompt -> prompt.startsWith("你是回复优化助手。"))
                .findFirst()
                .orElseThrow();
        assertTrue(finalizePrompt.contains("自然、简洁、可执行"));
        assertFalse(finalizePrompt.contains("你是新闻整理助手。"));
    }

    @Test
    void shouldBypassLlmSkillSelectionForSmallTalk() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("已收到"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("small-talk-user", "谢谢");

        assertEquals("llm", result.channel());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(1, llmClient.fallbackCallCount());
        assertEquals("llm-fallback", result.executionTrace().routing().route());
        assertTrue(result.executionTrace().routing().rejectedReasons().stream()
                .anyMatch(reason -> reason.contains("no deterministic rule") || reason.contains("no explicit SkillDSL")));
    }

    @Test
    void shouldRestrictLlmDispatcherPromptToShortlistedSkills() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"code.generate\",\"input\":{\"task\":\"修复 spring 接口 bug\"}}"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code and repair Spring API bugs", "代码修复完成"),
                new FixedSkill("eq.coach", "Coach emotional communication conflicts", "沟通建议"),
                new FixedSkill("teaching.plan", "Generate study plans and teaching plans", "学习计划")
        ), 1);

        DispatchResult result = service.dispatch("coding-user", "帮我修复 Spring 接口 bug");

        assertEquals("code.generate", result.channel());
        assertEquals(1, llmClient.routingCallCount());
        assertFalse(llmClient.routingPrompts().isEmpty());
        String routingPrompt = llmClient.routingPrompts().get(0);
        assertTrue(routingPrompt.contains("code.generate - Generate code and repair Spring API bugs"));
        assertFalse(routingPrompt.contains("eq.coach - Coach emotional communication conflicts"));
        assertFalse(routingPrompt.contains("teaching.plan - Generate study plans and teaching plans"));
        assertEquals("llm-dsl", result.executionTrace().routing().route());
        assertEquals("code.generate", result.executionTrace().routing().selectedSkill());
        assertTrue(result.executionTrace().routing().confidence() > 0.7);
    }

    @Test
    void shouldKeepGenericFallbackShortlistWhenNoSkillScoresPositive() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"echo\",\"input\":{\"text\":\"auto-routed by llm-dsl\"}}"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("echo", "Echo text for generic confirmation requests", "auto-routed by llm-dsl"),
                new FixedSkill("eq.coach", "Coach emotional communication conflicts", "沟通建议"),
                new FixedSkill("time", "Return current time", "12:00")
        ), 2);

        DispatchResult result = service.dispatch("generic-route-user", "请帮我自动处理这个请求");

        assertEquals("echo", result.channel());
        assertEquals(1, llmClient.routingCallCount());
        assertFalse(llmClient.routingPrompts().isEmpty());
        assertTrue(llmClient.routingPrompts().get(0).contains("echo - Echo text for generic confirmation requests"));
        assertEquals("llm-dsl", result.executionTrace().routing().route());
    }

    @Test
    void shouldRejectCodeGenerateWhenLlmRoutingMatchesGeneralKnowledgeQuestion() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"code.generate\",\"input\":{\"task\":\"用简单例子解释量子计算的基本原理\"}}",
                "量子计算可以理解为让信息在多个可能状态上同时演化，再通过干涉放大正确答案。"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "根据任务描述生成代码草稿", "代码草稿")
        ), 2);

        DispatchResult result = service.dispatch("general-user", "用简单例子解释量子计算的基本原理");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.routingCallCount());
        assertEquals(1, llmClient.fallbackCallCount());
        assertTrue(result.reply().contains("量子计算"));
    }

    @Test
    void shouldRouteViaSemanticAnalysisWhenHeuristicsSuggestLocalSkill() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建")
        ), 2, true);

        DispatchResult result = service.dispatch("semantic-user", "帮我创建一个待办，截止周五前提交周报");

        assertEquals("todo.create", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
        assertEquals("todo.create", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldPreserveExplicitSemanticAnalyzeIntent() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new SemanticTriggerSkill()
        ), 2, true);

        DispatchResult result = service.dispatch("semantic-user", "semantic 帮我修复 Spring 接口 bug");

        assertEquals("semantic.analyze", result.channel());
        assertEquals("detected-skill", result.executionTrace().routing().route());
        assertEquals(0, llmClient.routingCallCount());
    }

    @Test
    void shouldTreatJPrefixedContinueAsContinuationForHabitTodoRouting() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应调用 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new Skill() {
                    @Override
                    public String name() {
                        return "todo.create";
                    }

                    @Override
                    public String description() {
                        return "Creates todo items from natural language";
                    }

                    @Override
                    public SkillResult run(SkillContext context) {
                        return SkillResult.success(name(), "待办已创建");
                    }

                    @Override
                    public boolean supports(String input) {
                        return input != null && input.contains("待办");
                    }

                    @Override
                    public int routingScore(String input) {
                        return supports(input) ? 900 : Integer.MIN_VALUE;
                    }
                }
        ), 2);

        service.dispatch("habit-user", "帮我创建待办：周五前提交周报");
        service.dispatch("habit-user", "再创建一个待办：同步项目风险");

        DispatchResult result = service.dispatch("habit-user", "j继续");

        assertEquals("todo.create", result.channel());
        assertEquals("memory-habit", result.executionTrace().routing().route());
        assertEquals("todo.create", result.executionTrace().routing().selectedSkill());
        assertTrue(result.reply().contains("待办已创建"));
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldStoreRememberCommandFromChinesePrefixIntoTaskBucket() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("已记住"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        service.dispatch("memory-user", "记住任务：周五前提交周报并同步项目风险");

        List<SemanticMemoryEntry> entries = memoryManager.searchKnowledge("memory-user", "周报", 10, "task");
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).text().contains("周五前提交周报"));
    }

    @Test
    void shouldUseMemoryDirectReplyWithoutCallingLlmWhenRelevantMemoryExists() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("memory-user", "周五前提交周报并同步项目风险", List.of(0.2, 0.3), "task");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应调用 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("memory-user", "周报什么时候提交");

        assertEquals("memory.direct", result.channel());
        assertTrue(result.reply().contains("周五前提交周报"));
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldEscalateLocalFallbackToCloudWhenLocalReplyIndicatesFailure() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "[LLM local] request failed after 1 attempt(s). reason=timeout. Please retry later.",
                "cloud-recovered reply"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "local",
                "cost",
                false,
                "",
                false,
                "",
                "",
                "",
                true,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                true,
                false,
                true,
                "qwen",
                "quality",
                0,
                0,
                0
        );

        DispatchResult result = service.dispatch("escalation-user", "谢谢");

        assertEquals("llm", result.channel());
        assertEquals("cloud-recovered reply", result.reply());
        assertEquals(2, llmClient.fallbackCallCount());
        assertEquals("local", String.valueOf(llmClient.fallbackContexts().get(0).get("llmProvider")));
        assertEquals("qwen", String.valueOf(llmClient.fallbackContexts().get(1).get("llmProvider")));
        assertEquals(1, service.snapshotLocalEscalationMetrics().fallbackChainAttempts());
        assertEquals(1L, service.snapshotLocalEscalationMetrics().escalationReasons().get("timeout"));
    }

    @Test
    void shouldApplyStageMaxTokensToDslFallbackAndFinalizeContexts() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"echo\",\"input\":{\"text\":\"auto-routed by llm-dsl\"}}",
                "优化后的技能回复",
                "普通 fallback 回复"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(new FixedSkill("echo", "Echo text for generic confirmation requests", "raw echo output")),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "local",
                "cost",
                false,
                "",
                true,
                "echo",
                "",
                "",
                true,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                true,
                false,
                false,
                "qwen",
                "quality",
                111,
                222,
                77
        );

        DispatchResult routed = service.dispatch("budget-user", "请帮我自动处理这个请求");
        DispatchResult fallback = service.dispatch("budget-user", "谢谢");

        assertEquals("echo", routed.channel());
        assertEquals("llm", fallback.channel());
        assertEquals(111, ((Number) llmClient.routingContexts().get(0).get("maxTokens")).intValue());

        Map<String, Object> finalizeContext = llmClient.finalizeContexts().stream()
                .filter(ctx -> "skill-postprocess".equals(String.valueOf(ctx.get("routeStage"))))
                .findFirst()
                .orElseThrow();
        assertEquals(77, ((Number) finalizeContext.get("maxTokens")).intValue());

        Map<String, Object> fallbackContext = llmClient.fallbackContexts().stream()
                .filter(ctx -> "llm-fallback".equals(String.valueOf(ctx.get("routeStage"))))
                .findFirst()
                .orElseThrow();
        assertEquals(222, ((Number) fallbackContext.get("maxTokens")).intValue());
    }

    @Test
    void shouldEscalateToCloudWhenManualReasonIsRequested() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "local-first reply",
                "manual cloud reply"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "local",
                "cost",
                false,
                "",
                false,
                "",
                "",
                "",
                true,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                true,
                false,
                true,
                "qwen",
                "quality",
                0,
                0,
                0
        );

        DispatchResult result = service.dispatch("manual-escalation-user", "帮我分析下这个方案", Map.of(
                "localEscalationReason", "manual"
        ));

        assertEquals("llm", result.channel());
        assertEquals("manual cloud reply", result.reply());
        assertEquals("local", String.valueOf(llmClient.fallbackContexts().get(0).get("llmProvider")));
        assertEquals("qwen", String.valueOf(llmClient.fallbackContexts().get(1).get("llmProvider")));
        assertEquals("manual", String.valueOf(llmClient.fallbackContexts().get(1).get("localEscalationReason")));
        assertEquals(1L, service.snapshotLocalEscalationMetrics().escalationReasons().get("manual"));
    }

    @Test
    void shouldEscalateToCloudWhenLocalReplyLooksLowQualityForComplexInput() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "好的",
                "quality cloud reply"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "local",
                "cost",
                false,
                "",
                false,
                "",
                "",
                "",
                true,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                true,
                false,
                true,
                "qwen",
                "quality",
                0,
                0,
                0
        );

        DispatchResult result = service.dispatch("quality-escalation-user", "请给我一个系统架构方案并分析 tradeoff");

        assertEquals("llm", result.channel());
        assertEquals("quality cloud reply", result.reply());
        assertEquals("qwen", String.valueOf(llmClient.fallbackContexts().get(1).get("llmProvider")));
        assertEquals("quality", String.valueOf(llmClient.fallbackContexts().get(1).get("localEscalationReason")));
        assertEquals(1L, service.snapshotLocalEscalationMetrics().escalationReasons().get("quality"));
    }

    @Test
    void shouldReportLocalAndFallbackChainHitRatesInEscalationMetrics() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "普通回复",
                "[LLM local] request failed after 1 attempt(s). reason=timeout. Please retry later.",
                "cloud recovered"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(),
                2,
                "auto",
                0,
                "time",
                "",
                "",
                "local",
                "cost",
                false,
                "",
                false,
                "",
                "",
                "",
                true,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                true,
                false,
                true,
                "qwen",
                "quality",
                0,
                0,
                0
        );

        service.dispatch("metrics-escalation-user", "谢谢");
        service.dispatch("metrics-escalation-user", "再来一次");

        LocalEscalationMetricsDto metrics = service.snapshotLocalEscalationMetrics();
        assertEquals(2L, metrics.localAttempts());
        assertEquals(1L, metrics.localHits());
        assertEquals(0.5, metrics.localHitRate());
        assertEquals(1L, metrics.fallbackChainAttempts());
        assertEquals(1L, metrics.fallbackChainHits());
        assertEquals(1.0, metrics.fallbackChainHitRate());
        assertEquals(1L, metrics.escalationReasons().get("timeout"));
    }

    @Test
    void shouldExposeContextCompressionMetricsAfterLongConversation() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("已完成总结"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        for (int i = 0; i < 5; i++) {
            memoryManager.storeUserConversation("context-user", "用户消息-" + i + " 包含计划和截止时间");
            memoryManager.storeAssistantConversation("context-user", "助手回复-" + i + " 已同步负责人和下一步");
        }

        service.dispatch("context-user", "请继续总结当前安排");

        var metrics = service.snapshotContextCompressionMetrics();
        assertTrue(metrics.requests() >= 1);
        assertTrue(metrics.compressedRequests() >= 1);
        assertTrue(metrics.totalInputChars() > metrics.totalOutputChars());
        assertTrue(metrics.summarizedTurns() >= 1);
    }

    @Test
    void shouldShareChatHistoryAcrossProviders() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeUserConversation("shared-history-user", "上次的需求是完成报表");
        memoryManager.storeAssistantConversation("shared-history-user", "已记录报表需求");

        ContextRecordingLlmClient llmClient = new ContextRecordingLlmClient();
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        service.dispatch("shared-history-user", "谢谢", Map.of("llmProvider", "provider-a"));
        service.dispatch("shared-history-user", "好的", Map.of("llmProvider", "provider-b"));

        assertEquals(2, llmClient.contexts().size());
        Map<String, Object> firstContext = llmClient.contexts().get(0);
        Map<String, Object> secondContext = llmClient.contexts().get(1);

        String firstMemory = Objects.toString(firstContext.get("memoryContext"), "");
        String secondMemory = Objects.toString(secondContext.get("memoryContext"), "");
        assertTrue(firstMemory.contains("报表需求"));
        assertTrue(secondMemory.contains("报表需求"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstHistory = (List<Map<String, Object>>) firstContext.get("chatHistory");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondHistory = (List<Map<String, Object>>) secondContext.get("chatHistory");

        assertNotNull(firstHistory);
        assertNotNull(secondHistory);
        assertFalse(firstHistory.isEmpty());
        assertTrue(secondHistory.size() >= firstHistory.size());
        assertEquals(firstHistory, secondHistory.subList(0, firstHistory.size()));
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills, true);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               boolean semanticAnalysisEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills, semanticAnalysisEnabled, false);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               boolean semanticAnalysisEnabled,
                                               boolean braveFirstSearchRoutingEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                "auto", 0, "time", "", "", "", "", false, "", false, "", "", "",
                true, "天气,新闻,热点,实时", true, 280, true, semanticAnalysisEnabled, braveFirstSearchRoutingEnabled);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                "", "", "", "",
                false, "", false, "", "", "",
                true, "天气,新闻,热点,实时", true, 280, true);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled, realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                true);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean semanticAnalysisEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled, realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                semanticAnalysisEnabled, false);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean semanticAnalysisEnabled,
                                               boolean braveFirstSearchRoutingEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled, realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                semanticAnalysisEnabled, braveFirstSearchRoutingEnabled,
                false, "qwen", "quality", 0, 0, 0);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean semanticAnalysisEnabled,
                                               boolean braveFirstSearchRoutingEnabled,
                                               boolean localEscalationEnabled,
                                               String localEscalationCloudProvider,
                                               String localEscalationCloudPreset,
                                               int llmDslMaxTokens,
                                               int llmFallbackMaxTokens,
                                               int skillFinalizeMaxTokens) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled, realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                true, "eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*", 12000, "eq.coach timeout",
                semanticAnalysisEnabled, braveFirstSearchRoutingEnabled,
                localEscalationEnabled, localEscalationCloudProvider, localEscalationCloudPreset,
                llmDslMaxTokens, llmFallbackMaxTokens, skillFinalizeMaxTokens);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean preExecuteHeavySkillGuardEnabled,
                                               String preExecuteHeavySkillGuardSkills,
                                               long eqCoachTimeoutMs,
                                               String eqCoachTimeoutReply) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms, realtimeIntentMemoryShrinkEnabled,
                realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                preExecuteHeavySkillGuardEnabled, preExecuteHeavySkillGuardSkills, eqCoachTimeoutMs, eqCoachTimeoutReply,
                true);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean preExecuteHeavySkillGuardEnabled,
                                               String preExecuteHeavySkillGuardSkills,
                                               long eqCoachTimeoutMs,
                                               String eqCoachTimeoutReply,
                                               boolean semanticAnalysisEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled, realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                preExecuteHeavySkillGuardEnabled, preExecuteHeavySkillGuardSkills, eqCoachTimeoutMs, eqCoachTimeoutReply,
                semanticAnalysisEnabled, false);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean preExecuteHeavySkillGuardEnabled,
                                               String preExecuteHeavySkillGuardSkills,
                                               long eqCoachTimeoutMs,
                                               String eqCoachTimeoutReply,
                                               boolean semanticAnalysisEnabled,
                                               boolean braveFirstSearchRoutingEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                realtimeIntentBypassEnabled, realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled, realtimeIntentMemoryShrinkMaxChars, realtimeIntentMemoryShrinkIncludePersona,
                preExecuteHeavySkillGuardEnabled, preExecuteHeavySkillGuardSkills, eqCoachTimeoutMs, eqCoachTimeoutReply,
                semanticAnalysisEnabled, braveFirstSearchRoutingEnabled,
                false, "qwen", "quality", 0, 0, 0);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset,
                                               boolean realtimeIntentBypassEnabled,
                                               String realtimeIntentTerms,
                                               boolean realtimeIntentMemoryShrinkEnabled,
                                               int realtimeIntentMemoryShrinkMaxChars,
                                               boolean realtimeIntentMemoryShrinkIncludePersona,
                                               boolean preExecuteHeavySkillGuardEnabled,
                                               String preExecuteHeavySkillGuardSkills,
                                               long eqCoachTimeoutMs,
                                               String eqCoachTimeoutReply,
                                               boolean semanticAnalysisEnabled,
                                               boolean braveFirstSearchRoutingEnabled,
                                               boolean localEscalationEnabled,
                                               String localEscalationCloudProvider,
                                               String localEscalationCloudPreset,
                                               int llmDslMaxTokens,
                                               int llmFallbackMaxTokens,
                                               int skillFinalizeMaxTokens) {
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor, memoryManager);
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(
                llmClient,
                registry,
                semanticAnalysisEnabled,
                false,
                true,
                "",
                "local",
                "cost",
                120
        );
        SkillDslParser parser = new SkillDslParser(new SkillDslValidator());
        IntentModelRoutingPolicy intentModelRoutingPolicy = new IntentModelRoutingPolicy(
                false,
                "gpt",
                "gpt",
                "grok",
                "gemini",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "情绪,焦虑",
                180
        );
        MetaOrchestratorService metaOrchestratorService = new MetaOrchestratorService(false);
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        PersonaCoreService personaCoreService = new PersonaCoreService(memoryManager, false, 2, "unknown,null,n/a");
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.getLlmDsl().setProvider(llmDslProvider);
        tuningProperties.getLlmDsl().setPreset(llmDslPreset);
        tuningProperties.getLlmDsl().setMaxTokens(llmDslMaxTokens);
        tuningProperties.getLlmFallback().setProvider(llmFallbackProvider);
        tuningProperties.getLlmFallback().setPreset(llmFallbackPreset);
        tuningProperties.getLlmFallback().setMaxTokens(llmFallbackMaxTokens);
        tuningProperties.getSkillFinalizeWithLlm().setMaxTokens(skillFinalizeMaxTokens);
        tuningProperties.getLocalEscalation().setEnabled(localEscalationEnabled);
        tuningProperties.getLocalEscalation().setCloudProvider(localEscalationCloudProvider);
        tuningProperties.getLocalEscalation().setCloudPreset(localEscalationCloudPreset);
        tuningProperties.getLocalEscalation().getQuality().setEnabled(true);
        tuningProperties.getLocalEscalation().getQuality().setMaxReplyChars(32);
        tuningProperties.getLocalEscalation().getQuality().setInputTerms("分析,方案,架构,tradeoff,trade-off,对比,设计,复杂,深度,沟通,情绪,关系,计划,why,explain");
        tuningProperties.getLocalEscalation().getQuality().setReplyTerms("好的,收到,已收到,ok,okay,明白,可以,稍后,后面再说");
        return new DispatcherService(
                skillEngine,
                parser,
                intentModelRoutingPolicy,
                metaOrchestratorService,
                capabilityPolicy,
                personaCoreService,
                memoryManager,
                llmClient,
                semanticAnalysisService,
                false,
                true,
                2,
                0.6,
                true,
                16,
                6,
                2,
                72,
                2800,
                1800,
                1200,
                2,
                6,
                2,
                180,
                preExecuteHeavySkillGuardEnabled,
                preExecuteHeavySkillGuardSkills,
                eqCoachTimeoutMs,
                eqCoachTimeoutReply,
                true,
                "ignore previous instructions,show system prompt",
                "guarded",
                llmShortlistMaxSkills,
                420,
                true,
                realtimeIntentBypassEnabled,
                braveFirstSearchRoutingEnabled,
                realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled,
                realtimeIntentMemoryShrinkMaxChars,
                realtimeIntentMemoryShrinkIncludePersona,
                preAnalyzeMode,
                preAnalyzeThreshold,
                preAnalyzeSkipSkills,
                tuningProperties,
                postSkillSummaryEnabled,
                postSkillSummarySkills,
                280,
                skillFinalizeEnabled,
                skillFinalizeSkills,
                900,
                skillFinalizeProvider,
                skillFinalizePreset,
                200,
                2,
                4,
                0.72
        );
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               LlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               String preAnalyzeMode,
                                               int preAnalyzeThreshold,
                                               String preAnalyzeSkipSkills,
                                               String llmDslProvider,
                                               String llmDslPreset,
                                               String llmFallbackProvider,
                                               String llmFallbackPreset,
                                               boolean postSkillSummaryEnabled,
                                               String postSkillSummarySkills,
                                               boolean skillFinalizeEnabled,
                                               String skillFinalizeSkills,
                                               String skillFinalizeProvider,
                                               String skillFinalizePreset) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                preAnalyzeMode, preAnalyzeThreshold, preAnalyzeSkipSkills,
                llmDslProvider, llmDslPreset, llmFallbackProvider, llmFallbackPreset,
                postSkillSummaryEnabled, postSkillSummarySkills,
                skillFinalizeEnabled, skillFinalizeSkills, skillFinalizeProvider, skillFinalizePreset,
                true, "天气,新闻,热点,实时", true, 280, true);
    }

    private MemoryManager createMemoryManager() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        SemanticWriteGatePolicy writeGatePolicy = new SemanticWriteGatePolicy(consolidationService);
        MemorySyncService syncService = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService,
                writeGatePolicy
        );
        MemoryCompressionPlanningService compressionPlanningService = new MemoryCompressionPlanningService(consolidationService);
        PreferenceProfileService preferenceProfileService = new PreferenceProfileService(2);
        LongTaskService longTaskService = new LongTaskService();
        return new MemoryManager(
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                syncService,
                consolidationService,
                writeGatePolicy,
                compressionPlanningService,
                preferenceProfileService,
                longTaskService
        );
    }

    private Skill newMcpSkill(String skillName, String description, String output) {
        String[] parts = skillName.split("\\.", 3);
        String serverAlias = parts.length > 1 ? parts[1] : "docs";
        String toolName = parts.length > 2 ? parts[2] : skillName;
        return new McpToolSkill(
                new McpToolDefinition(serverAlias, "http://unused.local/mcp", toolName, description),
                new McpJsonRpcClient() {
                    @Override
                    public String callTool(String serverUrl, String toolName, Map<String, Object> arguments) {
                        return output;
                    }

                    @Override
                    public String callTool(String serverUrl,
                                           String toolName,
                                           Map<String, Object> arguments,
                                           Map<String, String> headers) {
                        return output;
                    }
                }
        );
    }

    private static final class SemanticTriggerSkill implements Skill {
        @Override
        public String name() {
            return "semantic.analyze";
        }

        @Override
        public String description() {
            return "Semantic analyzer trigger for tests";
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name(), "semantic analysis executed");
        }

        @Override
        public boolean supports(String input) {
            if (input == null) {
                return false;
            }
            String normalized = input.stripLeading().toLowerCase(Locale.ROOT);
            return normalized.startsWith("semantic");
        }
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final ArrayDeque<String> responses;
        private final List<String> routingPrompts = new ArrayList<>();
        private final List<Map<String, Object>> routingContexts = new ArrayList<>();
        private final List<Map<String, Object>> fallbackContexts = new ArrayList<>();
        private final List<Map<String, Object>> finalizeContexts = new ArrayList<>();
        private final List<String> fallbackPrompts = new ArrayList<>();
        private int routingCallCount;
        private int fallbackCallCount;

        private RecordingLlmClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public String generateResponse(String prompt, Map<String, Object> context) {
            if (prompt != null && prompt.startsWith("You are a dispatcher.")) {
                routingCallCount++;
                routingPrompts.add(prompt);
                routingContexts.add(context == null ? Map.of() : Map.copyOf(context));
            } else {
                fallbackCallCount++;
                Map<String, Object> captured = context == null ? Map.of() : Map.copyOf(context);
                fallbackContexts.add(captured);
                fallbackPrompts.add(prompt == null ? "" : prompt);
                if (prompt != null && (prompt.startsWith("你是回复优化助手。") || prompt.startsWith("你是新闻整理助手。"))) {
                    finalizeContexts.add(captured);
                }
            }
            return responses.isEmpty() ? "stub" : responses.removeFirst();
        }

        private int routingCallCount() {
            return routingCallCount;
        }

        private int fallbackCallCount() {
            return fallbackCallCount;
        }

        private List<String> routingPrompts() {
            return routingPrompts;
        }

        private List<Map<String, Object>> routingContexts() {
            return routingContexts;
        }

        private List<Map<String, Object>> fallbackContexts() {
            return fallbackContexts;
        }

        private List<Map<String, Object>> finalizeContexts() {
            return finalizeContexts;
        }

        private List<String> fallbackPrompts() {
            return fallbackPrompts;
        }
    }

    private static final class ContextRecordingLlmClient implements LlmClient {
        private final List<Map<String, Object>> contexts = new ArrayList<>();

        @Override
        public String generateResponse(String prompt, Map<String, Object> context) {
            contexts.add(context);
            return "stub";
        }

        private List<Map<String, Object>> contexts() {
            return contexts;
        }
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private List<String> messages() {
            return messages;
        }
    }

    private record FixedSkill(String name, String description, String output) implements Skill {
        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, output);
        }
    }
}

package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.memory.CentralMemoryRepository;
import com.zhongbo.mindos.assistant.memory.EpisodicMemoryService;
import com.zhongbo.mindos.assistant.memory.InMemoryCentralMemoryRepository;
import com.zhongbo.mindos.assistant.memory.LongTaskService;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.MemoryCompressionPlanningService;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.MemorySyncService;
import com.zhongbo.mindos.assistant.memory.PreferenceProfileService;
import com.zhongbo.mindos.assistant.memory.ProceduralMemoryService;
import com.zhongbo.mindos.assistant.memory.SemanticMemoryService;
import com.zhongbo.mindos.assistant.memory.SemanticWriteGatePolicy;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DefaultMemoryGateway;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.common.dto.LocalEscalationMetricsDto;
import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.DefaultSkillExecutionGateway;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.SkillRoutingProperties;
import com.zhongbo.mindos.assistant.skill.examples.TimeSkill;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolDefinition;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolExecutor;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
                scriptedSkill(
                        "mcp.qwensearch.webSearch",
                        "Search latest web news",
                        List.of("今天新闻", "最新新闻", "新闻", "news"),
                        context -> SkillResult.success("mcp.qwensearch.webSearch", "qwen result")
                ),
                scriptedSkill(
                        "mcp.bravesearch.webSearch",
                        "Brave latest web news search",
                        List.of("新闻", "news"),
                        context -> SkillResult.success("mcp.bravesearch.webSearch", "brave result")
                )
        ), 2);

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("mcp.qwensearch.webSearch", result.channel());
        assertEquals("qwen result", result.reply());
        assertTrue(Set.of("detected-skill", "detected-skill-parallel")
                .contains(result.executionTrace().routing().route()));
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
        assertTrue(Set.of("detected-skill", "detected-skill-parallel")
                .contains(result.executionTrace().routing().route()));
        assertEquals("mcp.bravesearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldRejectNewRequestsWhileDraining() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不会被调用"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        service.beginDrain();

        DispatchResult result = service.dispatch("drain-user", "hello");

        assertEquals("system.draining", result.channel());
        assertTrue(result.reply().contains("升级"));
        assertFalse(service.isAcceptingRequests());
    }

    @Test
    void shouldSelectBuiltInPreferredSearchSkillWhenParallelDetectedRoutingIsEnabled() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcherWithParallelDetectedRouting(
                memoryManager,
                llmClient,
                List.of(
                        newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "qwen result"),
                        newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "brave result")
                )
        );

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("mcp.bravesearch.webSearch", result.channel());
        assertEquals("brave result", result.reply());
        assertEquals("detected-skill-parallel", result.executionTrace().routing().route());
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
        assertEquals("detected-skill-parallel", result.executionTrace().routing().route());
        assertEquals("mcp.bravesearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldPreferBuiltInNewsSearchOverGenericMcpSearchWhenBothMatch() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                scriptedSkill(
                        "news_search",
                        "Built-in latest news aggregation",
                        List.of("新闻", "最新", "头条", "热点", "realtime", "latest"),
                        context -> SkillResult.success("news_search", "[news_search]\n关键词: 新闻\n摘要: builtin news result")
                ),
                newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "qwen result")
        ), 2, false, false);

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("news_search", result.channel());
        assertEquals("[news_search]\n关键词: 新闻\n摘要: builtin news result", result.reply());
        assertTrue(Set.of("detected-skill", "detected-skill-parallel").contains(result.executionTrace().routing().route()));
        assertEquals("news_search", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldKeepExplicitMcpSearchSelectionWhenProviderAliasIsRequested() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                scriptedSkill(
                        "news_search",
                        "Built-in latest news aggregation",
                        List.of("新闻", "最新", "头条", "热点", "realtime", "latest"),
                        context -> SkillResult.success("news_search", "[news_search]\n关键词: 新闻\n摘要: builtin news result")
                ),
                newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "qwen result")
        ), 2, false, false);

        DispatchResult result = service.dispatch("news-user", "用 qwensearch 查今天新闻");

        assertEquals("mcp.qwensearch.webSearch", result.channel());
        assertEquals("qwen result", result.reply());
        assertTrue(Set.of("detected-skill", "detected-skill-parallel").contains(result.executionTrace().routing().route()));
        assertEquals("mcp.qwensearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldPreferSerperSearchWhenSerperAndBraveAreBothAvailable() {
        System.setProperty("mindos.dispatcher.parallel-routing.search-priority-order",
                "mcp.serper.websearch,mcp.bravesearch.websearch");
        try {
            MemoryManager memoryManager = createMemoryManager();
            RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
            DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                    newMcpSkill("mcp.serper.webSearch", "Serper latest web search", "serper result"),
                    newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "brave result")
            ), 2, true, true);

            DispatchResult result = service.dispatch("news-user", "今天新闻");

            assertEquals("mcp.serper.webSearch", result.channel());
            assertEquals("serper result", result.reply());
            assertEquals("detected-skill-parallel", result.executionTrace().routing().route());
            assertEquals("mcp.serper.webSearch", result.executionTrace().routing().selectedSkill());
            assertEquals(0, llmClient.routingCallCount());
            assertEquals(0, llmClient.fallbackCallCount());
        } finally {
            System.clearProperty("mindos.dispatcher.parallel-routing.search-priority-order");
        }
    }

    @Test
    void shouldRouteSerpApiSearchWhenItIsTheOnlyConfiguredSearchSkill() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                newMcpSkill("mcp.serpapi.webSearch", "SerpApi precise web search", "serpapi result")
        ), 2, true, true);

        DispatchResult result = service.dispatch("news-user", "今天新闻");

        assertEquals("mcp.serpapi.webSearch", result.channel());
        assertEquals("serpapi result", result.reply());
        assertTrue(Set.of("detected-skill", "detected-skill-parallel")
                .contains(result.executionTrace().routing().route()));
        assertEquals("mcp.serpapi.webSearch", result.executionTrace().routing().selectedSkill());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldContinueWithQwenSearchWhenBraveResultIsLowRelevance() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                newMcpSkill("mcp.qwensearch.webSearch", "Search latest web news", "成都天气：今天多云，明天小雨"),
                newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "no result")
        ), 2, true, true);

        DispatchResult result = service.dispatch("news-user", "帮我查一下成都今天到明天天气");

        assertTrue(Set.of("mcp.qwensearch.webSearch", "mcp.bravesearch.webSearch").contains(result.channel()));
        assertFalse(result.reply().isBlank());
        assertTrue(Set.of("detected-skill-parallel", "semantic-analysis").contains(result.executionTrace().routing().route()));
        assertTrue(Set.of("mcp.qwensearch.webSearch", "mcp.bravesearch.webSearch").contains(result.executionTrace().routing().selectedSkill()));
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldUseLlmFallbackForRealtimeLikeInputEvenWhenBypassRoutingDisabled() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("成都今天多云，气温 18-24°C。"));
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
                "",
                "",
                false,
                "",
                false,
                "",
                "",
                "",
                false,
                "天气,气温,下雨,降雨,天气预报,新闻,热点,热搜,头条,汇率,股价,行情,油价,路况,航班,列车,比赛,比分,实时,最新,今日新闻",
                true,
                280,
                true,
                true,
                false
        );

        DispatchResult result = service.dispatch("weather-user", "今天成都天气");

        assertEquals("llm", result.channel());
        assertTrue(result.reply().contains("成都"));
        assertEquals(1, llmClient.fallbackCallCount());
        assertTrue(!result.reply().contains("根据已有记忆，我先直接回答"));
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
        assertTrue(result.reply().contains("这是整理后的新闻摘要"));
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
            DispatchResult qwenResult = qwenService.dispatch("news-user", "查看今天新闻 科技");

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
            DispatchResult braveResult = braveService.dispatch("news-user", "查看今天新闻 科技");

            List<String> qwenReasons = qwenResult.executionTrace().routing().reasons();
            assertTrue(qwenReasons.contains("realtimeLookup=true"), qwenReasons.toString());
            assertTrue(qwenReasons.contains("memoryDirectBypassed=true"), qwenReasons.toString());
            assertTrue(qwenReasons.contains("actualSearchSource=qwen"), qwenReasons.toString());

            List<String> braveReasons = braveResult.executionTrace().routing().reasons();
            assertTrue(braveReasons.contains("realtimeLookup=true"), braveReasons.toString());
            assertTrue(braveReasons.contains("memoryDirectBypassed=true"), braveReasons.toString());
            assertTrue(braveReasons.contains("actualSearchSource=brave"), braveReasons.toString());

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
                    List.of(scriptedSkill(
                            "mcp.bravesearch.webSearch",
                            "Brave latest web news search",
                            List.of("新闻", "news", "search"),
                            context -> SkillResult.failure("mcp.bravesearch.webSearch", "Brave timeout")
                    )),
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
            assertTrue(logs.contains("\"actualSearchSource\":\"qwen\""), logs);
            assertTrue(logs.contains("\"searchStatus\":\"success\""), logs);
            assertTrue(logs.contains("\"selectedSkill\":\"mcp.qwensearch.webSearch\""), logs);
            assertTrue(logs.contains("\"postprocessSent\":true"), logs);
            assertTrue(logs.contains("\"realtimeLookup\":true"), logs);
            assertTrue(logs.contains("\"memoryDirectBypassed\":true"), logs);
            assertTrue(logs.contains("\"finalChannel\":\"mcp.qwensearch.webSearch\""), logs);
            assertTrue(logs.contains("\"searchSource\":\"brave\""), logs);
            assertTrue(logs.contains("\"actualSearchSource\":\"brave\""), logs);
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
    void shouldKeepBuiltInNewsSearchRawOutputAndReportObservedSearchSource() {
        Logger logger = Logger.getLogger(DispatcherService.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            MemoryManager memoryManager = createMemoryManager();
            RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应被调用的 postprocess"));
            String rawNewsOutput = """
                    [news_search]
                    关键词: 科技新闻
                    摘要: 这是实时搜索返回的摘要。
                    来源: Serper
                    排序: latest
                    
                    1. 标题: Serper AI 观察
                       时间: 2026-04-03 11:00 Z
                       来源: Serper
                       详细链接: https://example.com/ai
                    """.trim();
            DispatcherService service = createDispatcher(
                    memoryManager,
                    llmClient,
                    List.of(scriptedSkill(
                            "news_search",
                            "Search latest news",
                            List.of("新闻", "latest", "realtime", "科技"),
                            context -> SkillResult.success("news_search", rawNewsOutput)
                    )),
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
                    "news_search,mcp.*",
                    "",
                    ""
            );

            DispatchResult result = service.dispatch("news-user", "查看今天新闻 科技");

            assertEquals("news_search", result.channel());
            assertEquals(rawNewsOutput, result.reply());
            assertTrue(llmClient.finalizeContexts().isEmpty(), "built-in news_search should not be dispatcher-postprocessed");

            String logs = String.join("\n", handler.messages());
            assertTrue(logs.contains("\"searchSource\":\"serper\""), logs);
            assertTrue(logs.contains("\"actualSearchSource\":\"serper\""), logs);
            assertTrue(logs.contains("\"searchAttempted\":true"), logs);
            assertTrue(logs.contains("\"searchStatus\":\"success\""), logs);
            assertTrue(logs.contains("\"selectedSkill\":\"news_search\""), logs);
            assertTrue(logs.contains("\"postprocessSent\":false"), logs);
            assertTrue(logs.contains("\"finalChannel\":\"news_search\""), logs);
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
    void shouldFilterDegradedMarkersAndWeatherNoiseFromNewsBrief() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "今日新闻标题：\n"
                        + "1. [[MINDOS_IM_DEGRADED|provider=local|category=timeout]]\n"
                        + "2. 成都天气预报,成都7天天气预报\n"
                        + "3. AI 芯片产业链再获融资\n"
                        + "总结：[[MINDOS_IM_DEGRADED|provider=local|category=timeout]]"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(newMcpSkill("mcp.bravesearch.webSearch",
                        "Brave latest web news search",
                        "1. AI 芯片产业链再获融资\n2. 机器人公司发布新平台\n3. 云服务厂商更新产品路线")),
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

        assertTrue(result.reply().startsWith("今日新闻标题："));
        assertFalse(result.reply().contains("MINDOS_IM_DEGRADED"));
        assertFalse(result.reply().contains("天气预报"));
        assertTrue(result.reply().contains("AI 芯片产业链再获融资"));
        assertTrue(result.reply().contains("总结："));
    }

    @Test
    void shouldPreferNewsDomainHeadlinesWhenOutputContainsWeatherLikeLines() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "今日新闻标题：\n"
                        + "1. 成都气温走势与降雨提醒\n"
                        + "2. 科技公司发布新一代 AI 芯片\n"
                        + "3. A股科技板块午后走强\n"
                        + "总结：市场关注科技与产业链进展"
        ));
        DispatcherService service = createDispatcher(
                memoryManager,
                llmClient,
                List.of(newMcpSkill("mcp.qwensearch.webSearch",
                        "Search latest web news",
                        "1. 科技公司发布新一代 AI 芯片\n2. A股科技板块午后走强\n3. 政策发布推动产业升级")),
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

        DispatchResult result = service.dispatch("news-user", "查看今天科技财经新闻");

        assertTrue(result.reply().startsWith("今日新闻标题："));
        assertFalse(result.reply().contains("气温走势"));
        assertTrue(result.reply().contains("AI 芯片"));
        assertTrue(result.reply().contains("A股科技板块"));
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
    void shouldPreferPrimaryPlannerBeforeLlmDispatcherShortlist() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"intent\":\"code.fix\",\"target\":\"code.generate\",\"params\":{\"task\":\"修复 spring 接口 bug\"},\"confidence\":0.91,\"requireClarify\":false}"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code and repair Spring API bugs", "代码修复完成"),
                new FixedSkill("eq.coach", "Coach emotional communication conflicts", "沟通建议"),
                new FixedSkill("teaching.plan", "Generate study plans and teaching plans", "学习计划")
        ), 1);

        DispatchResult result = service.dispatch("coding-user", "帮我修复 Spring 接口 bug");

        assertEquals("code.generate", result.channel());
        assertEquals(0, llmClient.routingCallCount());
        assertTrue(llmClient.routingPrompts().isEmpty());
        assertTrue(Set.of("semantic-analysis", "orchestrator-primary").contains(result.executionTrace().routing().route()));
        assertEquals("code.generate", result.executionTrace().routing().selectedSkill());
        assertTrue(result.executionTrace().routing().confidence() > 0.7);
    }

    @Test
    void shouldFallbackDirectlyWhenNoSkillScoresPositive() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"intent\":\"general.confirm\",\"target\":\"echo\",\"params\":{\"text\":\"auto-routed by llm-dsl\"},\"confidence\":0.82,\"requireClarify\":false}"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("echo", "Echo text for generic confirmation requests", "auto-routed by llm-dsl"),
                new FixedSkill("eq.coach", "Coach emotional communication conflicts", "沟通建议"),
                new FixedSkill("time", "Return current time", "12:00")
        ), 2);

        DispatchResult result = service.dispatch("generic-route-user", "请帮我自动处理这个请求");

        assertEquals("llm", result.channel());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(1, llmClient.fallbackCallCount());
        assertTrue(llmClient.routingPrompts().isEmpty());
    }

    @Test
    void shouldNotRouteToTimeSkillWhenTimeIsOnlyGenericContextWord() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("先按影响范围和紧急度排一下优先级。"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new TimeSkill()
        ), 2);

        DispatchResult result = service.dispatch("time-misroute-user", "这个任务时间比较紧，先帮我排一下优先级");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.fallbackCallCount());
        assertEquals("llm-fallback", result.executionTrace().routing().route());
    }

    @Test
    void shouldStillRouteToTimeSkillForExplicitCurrentTimeQuestion() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应进入 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new TimeSkill()
        ), 2);

        DispatchResult result = service.dispatch("time-user", "现在几点了");

        assertEquals("time", result.channel());
        assertEquals(0, llmClient.fallbackCallCount());
        assertTrue(result.reply().contains("时间"));
    }

    @Test
    void shouldFallbackToLlmWhenQuestionLooksLikeGeneralKnowledge() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "量子计算可以理解为让信息在多个可能状态上同时演化，再通过干涉放大正确答案。"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "根据任务描述生成代码草稿", "代码草稿")
        ), 2);

        DispatchResult result = service.dispatch("general-user", "用简单例子解释量子计算的基本原理");

        assertEquals("llm", result.channel());
        assertEquals(0, llmClient.routingCallCount());
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
    void shouldAskClarificationWhenSemanticConfidenceIsLow() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of(),
                        List.of("待办"),
                        "用户要创建待办",
                        0.55
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("clarify-user", "帮我弄个待办");

        assertEquals("semantic.clarify", result.channel());
        assertEquals("semantic-clarify", result.executionTrace().routing().route());
        assertTrue(result.reply().contains("todo.create"));
        assertTrue(result.reply().contains("关键参数"));
    }

    @Test
    void shouldSkipClarificationWhenCandidateIntentConfidenceForSuggestedSkillIsHigh() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of(),
                        List.of("待办"),
                        "用户要创建待办",
                        0.52,
                        List.of(
                                new SemanticAnalysisResult.CandidateIntent("todo.create", 0.86),
                                new SemanticAnalysisResult.CandidateIntent("eq.coach", 0.30)
                        )
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("semantic-user", "帮我弄个待办");

        assertEquals("todo.create", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
        assertEquals("todo.create", result.executionTrace().routing().selectedSkill());
    }

    @Test
    void shouldSkipClarificationWhenSemanticPayloadIsCompletedByRoutingDefaults() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of(),
                        List.of("待办"),
                        "用户要创建待办",
                        0.92
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("semantic-user", "帮我弄个待办");

        assertEquals("todo.create", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
    }

    @Test
    void shouldExecuteSemanticSkillWithRoutingCompletedPayload() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
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
                        return SkillResult.success(name(), "task=" + context.attributes().getOrDefault("task", ""));
                    }
                }
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of(),
                        List.of("待办"),
                        "",
                        0.92
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("semantic-user", "帮我弄个待办");

        assertEquals("todo.create", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
        assertTrue(result.reply().contains("task=帮我弄个待办"));
    }

    @Test
    void shouldRouteRealtimeSemanticIntentToLoadedMcpSkillInsteadOfSemanticAnalyze() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
                scriptedSkill(
                        "mcp.bravesearch.webSearch",
                        "Brave weather search",
                        List.of(),
                        context -> SkillResult.success("mcp.bravesearch.webSearch", "成都天气：今天多云，明天小雨")
                )
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "weather_query",
                        "查询成都今天的天气",
                        "semantic.analyze",
                        Map.of("location", "成都"),
                        List.of("天气", "成都"),
                        "查询成都今天的天气",
                        0.45,
                        List.of(
                                new SemanticAnalysisResult.CandidateIntent("semantic.analyze", 0.95),
                                new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.91)
                        )
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("weather-user", "今天成都的天气请查询");

        assertEquals("mcp.bravesearch.webSearch", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
        assertEquals("mcp.bravesearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertTrue(result.reply().contains("成都天气"));
    }

    @Test
    void shouldNotReturnMemoryDirectForRealtimeFallbackWhenRelevantMemoryExists() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("weather-user", "昨天成都天气：晴，适合外出", List.of(0.2, 0.3), "general");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("我正在按最新信息为你查询成都今天天气，请稍候。"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("weather-user", "今天成都天气");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(result.reply().contains("根据已有记忆，我先直接回答"));
    }

    @Test
    void shouldTreatBareWeatherQueryAsRealtimeInsteadOfMemoryDirect() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("weather-user", "昨天成都天气：晴，适合外出", List.of(0.2, 0.3), "general");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("我正在按最新天气信息为你查询成都的天气，请稍候。"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("weather-user", "成都天气");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(result.reply().contains("根据已有记忆，我先直接回答"));
    }

    @Test
    void shouldRouteRealtimeBySemanticAnalysisEvenWithoutKeywordInUserText() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("semantic weather reply"));
        SkillRegistry registry = new SkillRegistry(List.of(
                newMcpSkill("mcp.bravesearch.webSearch", "Brave latest web news search", "brave result")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "weather_query",
                        "帮我看下",
                        "mcp.bravesearch.webSearch",
                        Map.of("location", "成都"),
                        List.of("天气", "成都"),
                        "查询成都最新天气",
                        0.91,
                        List.of(
                                new SemanticAnalysisResult.CandidateIntent("semantic.analyze", 0.22),
                                new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.95)
                        )
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("weather-user", "帮我看下");

        assertEquals("mcp.bravesearch.webSearch", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
        assertEquals("mcp.bravesearch.webSearch", result.executionTrace().routing().selectedSkill());
        assertTrue(result.executionTrace().routing().reasons().toString().contains("semantic"), result.executionTrace().routing().reasons().toString());
        assertTrue(result.executionTrace().routing().reasons().toString().contains("realtime"), result.executionTrace().routing().reasons().toString());
    }

    @Test
    void shouldTreatBareNewsQueryAsRealtimeInsteadOfMemoryDirect() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("news-user", "semantic-summary intent=获取新闻, skill=news_search, summary=用户请求获取最近的国际新闻。", List.of(0.2, 0.3), "general");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("我正在按最新新闻信息为你查询国际新闻，请稍候。"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("news-user", "国际新闻");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(result.reply().contains("根据已有记忆，我先直接回答"));
    }

    @Test
    void shouldNotReturnMemoryDirectForRecentInternationalNewsWhenSemanticSummaryExists() {
        Logger logger = Logger.getLogger(DispatcherService.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("news-user", "semantic-summary intent=获取新闻, skill=news_search, summary=用户请求获取最近的国际新闻。", List.of(0.2, 0.3), "general");
        memoryManager.logSkillUsage("news-user", "news_search", "最近的国际新闻", true);
        memoryManager.logSkillUsage("news-user", "mcp.bravesearch.webSearch", "最近的国际新闻", true);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("这是最新国际新闻整理结果。"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);
        try {
            DispatchResult result = service.dispatch("news-user", "最近的国际新闻");

            assertEquals("llm", result.channel());
            assertEquals(1, llmClient.fallbackCallCount());
            assertFalse(result.reply().contains("根据已有记忆，我先直接回答"));
            List<String> reasons = result.executionTrace().routing().reasons();
            assertTrue(reasons.contains("realtimeLookup=true"), reasons.toString());
            assertTrue(reasons.contains("memoryDirectBypassed=true"), reasons.toString());
            assertTrue(reasons.contains("actualSearchSource="), reasons.toString());
            String logs = String.join("\n", handler.messages());
            assertTrue(logs.contains("\"realtimeLookup\":true"), logs);
            assertTrue(logs.contains("\"memoryDirectBypassed\":true"), logs);
            assertTrue(logs.contains("\"actualSearchSource\":\"\""), logs);
            assertTrue(logs.contains("\"finalChannel\":\"llm\""), logs);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldTreatCurrentWeatherAsRealtimeInsteadOfMemoryDirect() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("weather-user", "昨天成都天气：晴，适合外出", List.of(0.2, 0.3), "general");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("我正在按最新天气信息为你查询成都现在的天气，请稍候。"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("weather-user", "成都天气现在");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(result.reply().contains("根据已有记忆，我先直接回答"));
    }

    @Test
    void shouldStillRouteRepeatedNewsSearchToNewsSkillInsteadOfLoopBlocking() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.logSkillUsage("news-user", "news_search", "查看最新国际新闻", true);
        memoryManager.logSkillUsage("news-user", "news_search", "查看最新国际新闻", true);
        memoryManager.logSkillUsage("news-user", "news_search", "查看最新国际新闻", true);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应走到 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                scriptedSkill(
                        "news_search",
                        "Latest news lookup",
                        List.of("新闻", "news"),
                        context -> SkillResult.success("news_search", "news skill result")
                )
        ), 2);

        DispatchResult result = service.dispatch("news-user", "查看最新国际新闻");

        assertEquals("news_search", result.channel());
        assertEquals("news skill result", result.reply());
        assertTrue(Set.of("detected-skill", "semantic-analysis").contains(result.executionTrace().routing().route()));
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
        assertFalse(result.executionTrace().routing().reasons().toString().contains("loop guard"));
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
                scriptedSkill(
                        "todo.create",
                        "Creates todo items from natural language",
                        List.of("待办", "todo"),
                        context -> SkillResult.success("todo.create", "待办已创建")
                )
        ), 2);

        service.dispatch("habit-user", "帮我创建待办：周五前提交周报");
        service.dispatch("habit-user", "再创建一个待办：同步项目风险");

        DispatchResult result = service.dispatch("habit-user", "j继续");

        assertEquals("todo.create", result.channel());
        assertTrue(Set.of("memory-habit", "detected-skill").contains(result.executionTrace().routing().route()));
        assertEquals("todo.create", result.executionTrace().routing().selectedSkill());
        assertTrue(result.reply().contains("待办已创建"));
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldIgnoreReflectionEntriesWhenRoutingContinuationByHabit() {
        MemoryManager memoryManager = createMemoryManager();
        MemoryFacade memoryFacade = new MemoryFacade(memoryManager);
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应调用 llm"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                scriptedSkill(
                        "code.generate",
                        "Generate code from task descriptions",
                        List.of("generate code", "code"),
                        context -> SkillResult.success("code.generate", "代码草稿已生成")
                )
        ), 2);

        memoryFacade.logSkillUsage("reflection-habit-user", "code.generate", "generate code for order entity", true);
        memoryFacade.logSkillUsage("reflection-habit-user", "reflection", "reflection | pattern=stable", true);
        memoryFacade.logSkillUsage("reflection-habit-user", "code.generate", "generate code for order service", true);
        memoryFacade.logSkillUsage("reflection-habit-user", "reflection", "reflection | pattern=stable", true);

        DispatchResult result = service.dispatch("reflection-habit-user", "继续按之前方式");

        assertEquals("code.generate", result.channel());
        assertEquals("memory-habit", result.executionTrace().routing().route());
        assertEquals("code.generate", result.executionTrace().routing().selectedSkill());
        assertTrue(result.reply().contains("代码草稿已生成"));
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
    void shouldPreferLlmFallbackByDefaultEvenWhenRelevantMemoryExists() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("memory-user", "周五前提交周报并同步项目风险", List.of(0.2, 0.3), "task");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("这是模型整理后的默认回答"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        DispatchResult result = service.dispatch("memory-user", "周报什么时候提交");

        assertEquals("llm", result.channel());
        assertEquals("这是模型整理后的默认回答", result.reply());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(Objects.toString(llmClient.fallbackContexts().get(0).get("memoryContext"), "").isBlank());
    }

    @Test
    void shouldPreferLlmFallbackInLlmFirstModeEvenWhenRelevantMemoryExists() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("llm-first-user", "周五前提交周报并同步项目风险", List.of(0.2, 0.3), "task");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("这是模型整理后的回答"));
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.setAnswerMode("llm-first");
        DispatcherService service = createDispatcherWithTuning(memoryManager, llmClient, List.of(), 2, tuningProperties);

        DispatchResult result = service.dispatch("llm-first-user", "周报什么时候提交");

        assertEquals("llm", result.channel());
        assertEquals("这是模型整理后的回答", result.reply());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(Objects.toString(llmClient.fallbackContexts().get(0).get("memoryContext"), "").isBlank());
    }

    @Test
    void shouldUseMemoryDirectForExplicitRecallInLlmFirstMode() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("llm-first-memory-user", "周五前提交周报并同步项目风险", List.of(0.2, 0.3), "task");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应调用 llm"));
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.setAnswerMode("llm-first");
        DispatcherService service = createDispatcherWithTuning(memoryManager, llmClient, List.of(), 2, tuningProperties);

        DispatchResult result = service.dispatch("llm-first-memory-user", "根据记忆回答周报什么时候提交");

        assertEquals("memory.direct", result.channel());
        assertTrue(result.reply().contains("周五前提交周报"));
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldUseMemoryDirectForColloquialRecallInLlmFirstMode() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("llm-first-memory-colloquial-user", "周五前提交周报并同步项目风险", List.of(0.2, 0.3), "task");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应调用 llm"));
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.setAnswerMode("llm-first");
        DispatcherService service = createDispatcherWithTuning(memoryManager, llmClient, List.of(), 2, tuningProperties);

        DispatchResult result = service.dispatch("llm-first-memory-colloquial-user", "刚才说啥");

        assertEquals("memory.direct", result.channel());
        assertTrue(result.reply().contains("我回顾了一下我们之前的内容"));
        assertEquals(0, llmClient.fallbackCallCount());
    }

    @Test
    void shouldDisableMemoryReadsAndWritesUntilMemoryIsReenabled() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge("no-memory-user", "周五前提交周报并同步项目风险", List.of(0.2, 0.3), "task");
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("这次我直接按当前输入回答"));
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.setAnswerMode("llm-first");
        DispatcherService service = createDispatcherWithTuning(memoryManager, llmClient, List.of(), 2, tuningProperties);

        DispatchResult disable = service.dispatch("no-memory-user", "不要记忆");
        DispatchResult disabledQuery = service.dispatch("no-memory-user", "周报什么时候提交");
        int conversationCountWhileDisabled = memoryManager.getRecentConversation("no-memory-user", 10).size();
        DispatchResult enable = service.dispatch("no-memory-user", "恢复记忆");
        DispatchResult recall = service.dispatch("no-memory-user", "根据记忆回答周报什么时候提交");

        assertEquals("memory.mode", disable.channel());
        assertEquals("llm", disabledQuery.channel());
        assertEquals("这次我直接按当前输入回答", disabledQuery.reply());
        assertEquals(1, llmClient.fallbackCallCount());
        assertEquals("", Objects.toString(llmClient.fallbackContexts().get(0).get("memoryContext"), ""));
        assertEquals(0, conversationCountWhileDisabled);
        assertEquals("memory.mode", enable.channel());
        assertEquals("memory.direct", recall.channel());
        assertTrue(recall.reply().contains("周五前提交周报"));
    }

    @Test
    void shouldStoreSemanticSummaryWithKeyParamsDigest() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of("task", "提交周报", "dueDate", "周五"),
                        List.of("待办", "周报"),
                        "用户要创建待办事项",
                        0.93
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        service.dispatch("semantic-memory-user", "帮我创建待办：周五前提交周报");

        List<SemanticMemoryEntry> entries = memoryManager.searchKnowledge("semantic-memory-user", "意图摘要", 10, "task");
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("[意图摘要]")));
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("用户当前想要：用户要创建待办事项")));
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("可用执行方式：todo.create")));
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("已确认信息：task=提交周报")));
    }

    @Test
    void shouldStoreConversationRollupWithoutReplyPollution() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建：周五前提交周报")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of("task", "提交周报", "dueDate", "周五"),
                        List.of("待办", "周报"),
                        "用户要创建待办事项",
                        0.93
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        service.dispatch("conversation-rollup-user", "帮我创建待办：周五前提交周报");

        List<SemanticMemoryEntry> entries = memoryManager.searchKnowledge("conversation-rollup-user", "助手上下文", 10, "conversation-rollup");
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("[助手上下文]")));
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("结果：已推进")));
        assertTrue(entries.stream().anyMatch(entry -> entry.text().contains("关键信息：dueDate=周五, task=提交周报")));
        assertTrue(entries.stream().noneMatch(entry -> entry.text().contains("reply=")));
    }

    @Test
    void shouldAppendProactiveHintForStructuredTaskExecution() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("不应调用 llm"));
        SkillRegistry registry = new SkillRegistry(List.of(
                new FixedSkill("todo.create", "Creates todo items from natural language", "待办已创建")
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of("task", "提交周报", "dueDate", "周五前", "nextAction", "同步项目风险"),
                        List.of("待办", "周报"),
                        "用户要创建待办事项",
                        0.93
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("proactive-skill-user", "帮我创建待办：周五前提交周报");

        assertEquals("todo.create", result.channel());
        assertTrue(result.reply().contains("待办已创建"));
        assertTrue(result.reply().contains("下一步建议：先同步项目风险。"));
        assertTrue(result.reply().contains("需要的话我可以直接继续做这一步"));
        assertTrue(result.executionTrace().routing().reasons().stream().anyMatch(reason -> reason.startsWith("proactiveHint=")));
    }

    @Test
    void shouldAppendProactiveHintForShortLlmContinuationReplies() {
        MemoryManager memoryManager = createMemoryManager();
        memoryManager.storeKnowledge(
                "proactive-llm-user",
                "[任务状态] 当前事项：提交周报；状态：进行中；下一步：同步项目风险",
                List.of(0.2, 0.3),
                "task"
        );
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("好的，我继续跟进。"));
        SkillRegistry registry = new SkillRegistry(List.of());
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "继续当前任务",
                        "继续推进：提交周报",
                        "",
                        Map.of(),
                        List.of("继续", "周报"),
                        "继续推进当前事项",
                        0.81
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        DispatchResult result = service.dispatch("proactive-llm-user", "继续");

        assertEquals("llm", result.channel());
        assertTrue(result.reply().contains("好的，我继续跟进。"));
        assertTrue(result.reply().contains("下一步建议：先同步项目风险。"));
        assertTrue(result.executionTrace().routing().reasons().stream().anyMatch(reason -> reason.startsWith("proactiveHint=")));
    }

    @Test
    void shouldApplyBehaviorLearnedDefaultParamsToSemanticPayload() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
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
                        return SkillResult.success(name(), "dueDate=" + context.attributes().getOrDefault("dueDate", ""));
                    }
                }
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of("task", "提交周报"),
                        List.of("待办"),
                        "用户要创建待办",
                        0.91
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        service.dispatch("behavior-user", "skill:todo.create task=日报1 dueDate=周五");
        service.dispatch("behavior-user", "skill:todo.create task=日报2 dueDate=周五");
        DispatchResult result = service.dispatch("behavior-user", "帮我创建一个待办");

        assertEquals("todo.create", result.channel());
        assertTrue(result.reply().contains("dueDate=周五"));
        List<SemanticMemoryEntry> behaviorEntries = memoryManager.searchKnowledge("behavior-user", "behavior-profile", 10, "task");
        assertTrue(behaviorEntries.stream().anyMatch(entry -> entry.text().contains("defaults=dueDate=周五")));
    }

    @Test
    void shouldKeepSemanticBackfillAndBehaviorDefaultsForImRequests() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("stub"));
        SkillRegistry registry = new SkillRegistry(List.of(
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
                        return SkillResult.success(
                                name(),
                                "imPlatform=" + context.attributes().getOrDefault("imPlatform", "")
                                        + ",task=" + context.attributes().getOrDefault("task", "")
                                        + ",dueDate=" + context.attributes().getOrDefault("dueDate", "")
                        );
                    }
                }
        ));
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()), true, false, true, "", "local", "cost", 120) {
            @Override
            public SemanticAnalysisResult analyze(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext,
                                                  List<String> availableSkillSummaries) {
                return new SemanticAnalysisResult(
                        "llm",
                        "创建待办",
                        userInput,
                        "todo.create",
                        Map.of(),
                        List.of("待办", "周报"),
                        "提交周报",
                        0.91
                );
            }
        };
        DispatcherService service = createDispatcherWithSemanticService(memoryManager, llmClient, registry, semanticAnalysisService, 2);

        Map<String, Object> imProfile = Map.of(
                "imPlatform", "dingtalk",
                "imSenderId", "sender-1",
                "imChatId", "chat-1"
        );

        service.dispatch("im-user", "skill:todo.create task=日报1 dueDate=周五", imProfile);
        service.dispatch("im-user", "skill:todo.create task=日报2 dueDate=周五", imProfile);

        DispatchResult result = service.dispatch("im-user", "请帮我安排一个待办", imProfile);

        assertEquals("todo.create", result.channel());
        assertEquals("semantic-analysis", result.executionTrace().routing().route());
        assertTrue(result.reply().contains("imPlatform=dingtalk"));
        assertTrue(result.reply().contains("task=请帮我安排一个待办"));
        assertTrue(result.reply().contains("dueDate=周五"));
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
    void shouldApplyConfiguredFallbackAndCloudModels() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "[LLM local] request failed after 1 attempt(s). reason=timeout. Please retry later.",
                "cloud-recovered reply"
        ));
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.getLlmFallback().setProvider("local");
        tuningProperties.getLlmFallback().setPreset("cost");
        tuningProperties.getLlmFallback().setModel("tinyllama");
        tuningProperties.getLocalEscalation().setEnabled(true);
        tuningProperties.getLocalEscalation().setCloudProvider("qwen");
        tuningProperties.getLocalEscalation().setCloudPreset("quality");
        tuningProperties.getLocalEscalation().setCloudModel("qwen3.6-plus");
        DispatcherService service = createDispatcherWithTuning(memoryManager, llmClient, List.of(), 2, tuningProperties);

        DispatchResult result = service.dispatch("model-routing-user", "谢谢");

        assertEquals("llm", result.channel());
        assertEquals("cloud-recovered reply", result.reply());
        assertEquals("tinyllama", String.valueOf(llmClient.fallbackContexts().get(0).get("model")));
        assertEquals("qwen3.6-plus", String.valueOf(llmClient.fallbackContexts().get(1).get("model")));
    }

    @Test
    void shouldEscalateDirectlyWhenLocalResourceGuardTriggers() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("resource-guard cloud reply"));
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        tuningProperties.getLlmFallback().setProvider("local");
        tuningProperties.getLlmFallback().setPreset("cost");
        tuningProperties.getLlmFallback().setModel("tinyllama");
        tuningProperties.getLocalEscalation().setEnabled(true);
        tuningProperties.getLocalEscalation().setCloudProvider("qwen");
        tuningProperties.getLocalEscalation().setCloudPreset("quality");
        tuningProperties.getLocalEscalation().getResourceGuard().setEnabled(true);
        tuningProperties.getLocalEscalation().getResourceGuard().setMinFreeMemoryMb(Integer.MAX_VALUE);
        DispatcherService service = createDispatcherWithTuning(memoryManager, llmClient, List.of(), 2, tuningProperties);

        DispatchResult result = service.dispatch("resource-guard-user", "谢谢");

        assertEquals("llm", result.channel());
        assertEquals("resource-guard cloud reply", result.reply());
        assertEquals(1, llmClient.fallbackCallCount());
        assertEquals("qwen", String.valueOf(llmClient.fallbackContexts().get(0).get("llmProvider")));
        assertEquals("resource_guard", String.valueOf(llmClient.fallbackContexts().get(0).get("localEscalationReason")));
        assertEquals(1L, service.snapshotLocalEscalationMetrics().escalationReasons().get("resource_guard"));
    }

    @Test
    void shouldApplyStageMaxTokensToFallbackAndFinalizeContextsWithoutDslRouting() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
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

        DispatchResult routed = service.dispatch("budget-user", "skill:echo text=auto-routed by llm-dsl");
        DispatchResult fallback = service.dispatch("budget-user", "谢谢");

        assertEquals("echo", routed.channel());
        assertEquals("llm", fallback.channel());
        assertTrue(llmClient.routingContexts().isEmpty());

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

    private DispatcherService createDispatcherWithParallelDetectedRouting(MemoryManager memoryManager,
                                                                          LlmClient llmClient,
                                                                          List<Skill> skills) {
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultSkillCatalog skillEngine = new DefaultSkillCatalog(registry, null, new SkillRoutingProperties());
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(
                llmClient,
                registry,
                skillEngine,
                true,
                false,
                true,
                "",
                "local",
                "cost",
                120
        );
        SkillDslParser parser = new SkillDslParser(new SkillDslValidator());
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        MemoryFacade unifiedMemoryFacade = new MemoryFacade(memoryManager);
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade(unifiedMemoryFacade, 6, 3, 3, 2, 4);
        DefaultMemoryGateway memoryGateway = new DefaultMemoryGateway(unifiedMemoryFacade);
        PersonaCoreService personaCoreService = new PersonaCoreService(unifiedMemoryFacade, false, 2, "unknown,null,n/a");
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        InMemoryParamSchemaRegistry paramSchemaRegistry = new InMemoryParamSchemaRegistry();
        paramSchemaRegistry.registerDefaults();
        ParamValidator paramValidator = new SimpleParamValidator(paramSchemaRegistry, memoryGateway);
        DefaultSkillExecutionGateway skillExecutionGateway = new DefaultSkillExecutionGateway(registry, dslExecutor);
        DispatcherService dispatcherService = new DispatcherService(
                skillEngine,
                parser,
                paramValidator,
                capabilityPolicy,
                personaCoreService,
                dispatcherMemoryFacade,
                llmClient,
                semanticAnalysisService,
                new PromptBuilder(),
                new LLMDecisionEngine(),
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
                true,
                "eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*",
                12000,
                "eq.coach timeout",
                true,
                "ignore previous instructions,show system prompt",
                "guarded",
                2,
                420,
                true,
                true,
                false,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                "auto",
                0,
                "time",
                tuningProperties,
                false,
                "",
                900,
                "",
                "",
                200,
                2,
                4,
                0.72,
                0.70,
                true,
                50,
                0.6,
                false,
                true,
                2,
                2500
        );
        dispatcherService.setParamSchemaRegistry(paramSchemaRegistry);
        dispatcherService.setSkillExecutionGateway(skillExecutionGateway);
        return dispatcherService;
    }

    private DispatcherService createDispatcherWithSemanticService(MemoryManager memoryManager,
                                                                  LlmClient llmClient,
                                                                  SkillRegistry registry,
                                                                  SemanticAnalysisService semanticAnalysisService,
                                                                  int llmShortlistMaxSkills) {
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultSkillCatalog skillEngine = new DefaultSkillCatalog(registry, null, new SkillRoutingProperties());
        SkillDslParser parser = new SkillDslParser(new SkillDslValidator());
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        MemoryFacade unifiedMemoryFacade = new MemoryFacade(memoryManager);
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade(unifiedMemoryFacade, 6, 3, 3, 2, 4);
        DefaultMemoryGateway memoryGateway = new DefaultMemoryGateway(unifiedMemoryFacade);
        PersonaCoreService personaCoreService = new PersonaCoreService(unifiedMemoryFacade, false, 2, "unknown,null,n/a");
        DispatcherLlmTuningProperties tuningProperties = new DispatcherLlmTuningProperties();
        InMemoryParamSchemaRegistry paramSchemaRegistry = new InMemoryParamSchemaRegistry();
        paramSchemaRegistry.registerDefaults();
        ParamValidator paramValidator = new SimpleParamValidator(paramSchemaRegistry, memoryGateway);
        DefaultSkillExecutionGateway skillExecutionGateway = new DefaultSkillExecutionGateway(registry, dslExecutor);
        DispatcherService dispatcherService = new DispatcherService(
                skillEngine,
                parser,
                paramValidator,
                capabilityPolicy,
                personaCoreService,
                dispatcherMemoryFacade,
                llmClient,
                semanticAnalysisService,
                new PromptBuilder(),
                new LLMDecisionEngine(),
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
                true,
                "eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*",
                12000,
                "eq.coach timeout",
                true,
                "ignore previous instructions,show system prompt",
                "guarded",
                llmShortlistMaxSkills,
                420,
                true,
                true,
                false,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                "auto",
                0,
                "time",
                tuningProperties,
                false,
                "",
                900,
                "",
                "",
                200,
                2,
                4,
                0.72,
                0.70,
                true,
                50,
                0.6,
                false,
                false,
                2,
                2500
        );
        dispatcherService.setParamSchemaRegistry(paramSchemaRegistry);
        dispatcherService.setSkillExecutionGateway(skillExecutionGateway);
        return dispatcherService;
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
        DefaultSkillCatalog skillEngine = new DefaultSkillCatalog(registry, null, new SkillRoutingProperties());
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(
                llmClient,
                registry,
                skillEngine,
                semanticAnalysisEnabled,
                false,
                true,
                "",
                "local",
                "cost",
                120
        );
        SkillDslParser parser = new SkillDslParser(new SkillDslValidator());
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        MemoryFacade unifiedMemoryFacade = new MemoryFacade(memoryManager);
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade(unifiedMemoryFacade, 6, 3, 3, 2, 4);
        DefaultMemoryGateway memoryGateway = new DefaultMemoryGateway(unifiedMemoryFacade);
        PersonaCoreService personaCoreService = new PersonaCoreService(unifiedMemoryFacade, false, 2, "unknown,null,n/a");
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
        InMemoryParamSchemaRegistry paramSchemaRegistry = new InMemoryParamSchemaRegistry();
        paramSchemaRegistry.registerDefaults();
        ParamValidator paramValidator = new SimpleParamValidator(paramSchemaRegistry, memoryGateway);
        DefaultSkillExecutionGateway skillExecutionGateway = new DefaultSkillExecutionGateway(registry, dslExecutor);
        DispatcherService dispatcherService = new DispatcherService(
                skillEngine,
                parser,
                paramValidator,
                capabilityPolicy,
                personaCoreService,
                dispatcherMemoryFacade,
                llmClient,
                semanticAnalysisService,
                new PromptBuilder(),
                new LLMDecisionEngine(),
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
                skillFinalizeEnabled,
                skillFinalizeSkills,
                900,
                skillFinalizeProvider,
                skillFinalizePreset,
                200,
                2,
                4,
                0.72,
                0.70,
                true,
                50,
                0.6,
                false,
                false,
                2,
                2500
        );
        dispatcherService.setParamSchemaRegistry(paramSchemaRegistry);
        dispatcherService.setSkillExecutionGateway(skillExecutionGateway);
        return dispatcherService;
    }

    private DispatcherService createDispatcherWithTuning(MemoryManager memoryManager,
                                                         LlmClient llmClient,
                                                         List<Skill> skills,
                                                         int llmShortlistMaxSkills,
                                                         DispatcherLlmTuningProperties tuningProperties) {
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultSkillCatalog skillEngine = new DefaultSkillCatalog(registry, null, new SkillRoutingProperties());
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(
                llmClient,
                registry,
                skillEngine,
                true,
                false,
                true,
                "",
                "local",
                "cost",
                120
        );
        SkillDslParser parser = new SkillDslParser(new SkillDslValidator());
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        MemoryFacade unifiedMemoryFacade = new MemoryFacade(memoryManager);
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade(unifiedMemoryFacade, 6, 3, 3, 2, 4);
        DefaultMemoryGateway memoryGateway = new DefaultMemoryGateway(unifiedMemoryFacade);
        PersonaCoreService personaCoreService = new PersonaCoreService(unifiedMemoryFacade, false, 2, "unknown,null,n/a");
        InMemoryParamSchemaRegistry paramSchemaRegistry = new InMemoryParamSchemaRegistry();
        paramSchemaRegistry.registerDefaults();
        ParamValidator paramValidator = new SimpleParamValidator(paramSchemaRegistry, memoryGateway);
        DefaultSkillExecutionGateway skillExecutionGateway = new DefaultSkillExecutionGateway(registry, dslExecutor);
        DispatcherService dispatcherService = new DispatcherService(
                skillEngine,
                parser,
                paramValidator,
                capabilityPolicy,
                personaCoreService,
                dispatcherMemoryFacade,
                llmClient,
                semanticAnalysisService,
                new PromptBuilder(),
                new LLMDecisionEngine(),
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
                true,
                "eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*",
                12000,
                "eq.coach timeout",
                true,
                "ignore previous instructions,show system prompt",
                "guarded",
                llmShortlistMaxSkills,
                420,
                true,
                true,
                false,
                "天气,新闻,热点,实时",
                true,
                280,
                true,
                "auto",
                0,
                "time",
                tuningProperties,
                false,
                "",
                900,
                "",
                "",
                200,
                2,
                4,
                0.72,
                0.70,
                true,
                50,
                0.6,
                false,
                false,
                2,
                2500
        );
        dispatcherService.setParamSchemaRegistry(paramSchemaRegistry);
        dispatcherService.setSkillExecutionGateway(skillExecutionGateway);
        return dispatcherService;
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

    private DispatcherMemoryFacade dispatcherMemoryFacade(MemoryGateway memoryGateway) {
        return new DispatcherMemoryFacade(memoryGateway, null, null);
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
        return new TestMcpLikeSkill(
                new McpToolDefinition(serverAlias, "http://unused.local/mcp", toolName, description),
                output
        );
    }

    private static final class SemanticTriggerSkill implements Skill, SkillDescriptorProvider {
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
        public SkillDescriptor skillDescriptor() {
            return new SkillDescriptor(name(), description(), List.of("semantic", "semantic analyze"));
        }
    }

    private static final class FixedNamedSkill implements Skill, SkillDescriptorProvider {
        private final String name;
        private final String description;
        private final String output;

        private FixedNamedSkill(String name, String description, String output) {
            this.name = name;
            this.description = description;
            this.output = output;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public SkillDescriptor skillDescriptor() {
            return new SkillDescriptor(name, description, List.of(name, description));
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, output);
        }
    }

    private static final class TestMcpLikeSkill implements Skill, SkillDescriptorProvider {
        private final McpToolDefinition toolDefinition;
        private final String output;

        private TestMcpLikeSkill(McpToolDefinition toolDefinition, String output) {
            this.toolDefinition = toolDefinition;
            this.output = output;
        }

        @Override
        public String name() {
            return toolDefinition.skillName();
        }

        @Override
        public String description() {
            return toolDefinition.description();
        }

        @Override
        public SkillDescriptor skillDescriptor() {
            List<String> keywords = new ArrayList<>();
            keywords.add(name());
            keywords.add(description());
            keywords.add(toolDefinition.name());
            keywords.add(toolDefinition.serverAlias());
            String normalized = (toolDefinition.name() + " " + description()).toLowerCase(Locale.ROOT);
            if (normalized.contains("search")) {
                keywords.addAll(List.of("search", "web search", "搜", "搜索", "查询", "查一下"));
            }
            if (normalized.contains("websearch") || normalized.contains("web search")) {
                keywords.addAll(List.of("news", "latest", "realtime", "weather", "新闻", "最新", "实时", "天气"));
            }
            if (normalized.contains("news") || normalized.contains("latest") || normalized.contains("headline")) {
                keywords.addAll(List.of("news", "latest", "realtime", "新闻", "最新", "实时", "头条", "热点"));
            }
            if (normalized.contains("weather")) {
                keywords.addAll(List.of("weather", "天气"));
            }
            if (normalized.contains("docs") || normalized.contains("documentation") || normalized.contains("guide")) {
                keywords.addAll(List.of("docs", "documentation", "guide", "文档", "手册", "指南"));
            }
            return new SkillDescriptor(name(), description(), keywords.stream().distinct().toList());
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(toolDefinition.skillName(), output);
        }
    }

    private Skill scriptedSkill(String name,
                                String description,
                                List<String> routingKeywords,
                                Function<SkillContext, SkillResult> runner) {
        return new ScriptedSkill(name, description, routingKeywords, runner);
    }

    private static final class ScriptedSkill implements Skill, SkillDescriptorProvider {
        private final String name;
        private final String description;
        private final List<String> routingKeywords;
        private final Function<SkillContext, SkillResult> runner;

        private ScriptedSkill(String name,
                              String description,
                              List<String> routingKeywords,
                              Function<SkillContext, SkillResult> runner) {
            this.name = name;
            this.description = description;
            this.routingKeywords = routingKeywords == null ? List.of() : List.copyOf(routingKeywords);
            this.runner = runner;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public SkillDescriptor skillDescriptor() {
            return new SkillDescriptor(name, description, routingKeywords);
        }

        @Override
        public SkillResult run(SkillContext context) {
            return runner.apply(context);
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

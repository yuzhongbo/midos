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
import com.zhongbo.mindos.assistant.common.dto.RoutingReplayDatasetDto;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherServiceTest {

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
                                               RecordingLlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills,
                                               boolean semanticAnalysisEnabled) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills,
                "auto", 0, "time", "", "", "", "", false, "", false, "", "", "",
                true, "天气,新闻,热点,实时", true, 280, true, semanticAnalysisEnabled);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               RecordingLlmClient llmClient,
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
                                               RecordingLlmClient llmClient,
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
                                               RecordingLlmClient llmClient,
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
                true, "eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*", 12000, "eq.coach timeout",
                semanticAnalysisEnabled);
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               RecordingLlmClient llmClient,
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
                                               RecordingLlmClient llmClient,
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
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor, memoryManager);
        SemanticAnalysisService semanticAnalysisService = new SemanticAnalysisService(llmClient, registry, semanticAnalysisEnabled, false, "");
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
                "情绪,焦虑",
                180
        );
        MetaOrchestratorService metaOrchestratorService = new MetaOrchestratorService(false);
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        PersonaCoreService personaCoreService = new PersonaCoreService(memoryManager, false, 2, "unknown,null,n/a");
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
                true,
                realtimeIntentBypassEnabled,
                realtimeIntentTerms,
                realtimeIntentMemoryShrinkEnabled,
                realtimeIntentMemoryShrinkMaxChars,
                realtimeIntentMemoryShrinkIncludePersona,
                preAnalyzeMode,
                preAnalyzeThreshold,
                preAnalyzeSkipSkills,
                llmDslProvider,
                llmDslPreset,
                llmFallbackProvider,
                llmFallbackPreset,
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
                                               RecordingLlmClient llmClient,
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
                if (prompt != null && prompt.startsWith("你是回复优化助手。")) {
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

    private record FixedSkill(String name, String description, String output) implements Skill {
        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, output);
        }
    }
}

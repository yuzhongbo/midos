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
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldSkipLlmPreAnalyzeWhenModeIsNever() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"code.generate\",\"input\":{\"task\":\"generate code\"}}",
                "fallback"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code", "code")
        ), 2, "never", 18, "time", "", "", "", "", false, "", false, "", "", "");

        DispatchResult result = service.dispatch("pre-analyze-never-user", "帮我看看这个需求");

        assertEquals("llm", result.channel());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(1, llmClient.fallbackCallCount());
        assertEquals("never", service.snapshotSkillPreAnalyzeMetrics().mode());
        assertTrue(service.snapshotSkillPreAnalyzeMetrics().skippedByGate() >= 1);
    }

    @Test
    void shouldSkipLlmPreAnalyzeWhenConfidenceIsBelowThresholdInAutoMode() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("fallback"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code", "code")
        ), 2, "auto", 200, "time", "", "", "", "", false, "", false, "", "", "");

        DispatchResult result = service.dispatch("pre-analyze-threshold-user", "你好，今天天气不错");

        assertEquals("llm", result.channel());
        assertEquals(0, llmClient.routingCallCount());
        assertEquals(1, llmClient.fallbackCallCount());
    }

    @Test
    void shouldRejectPreAnalyzeResultForSkippedSkill() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"code.generate\",\"input\":{\"task\":\"create api\"}}",
                "fallback"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code", "code")
        ), 2, "always", 0, "code.generate,time", "", "", "", "", false, "", false, "", "", "");

        DispatchResult result = service.dispatch("pre-analyze-skip-user", "请处理这个请求");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.routingCallCount());
        assertEquals(1, llmClient.fallbackCallCount());
    }

    @Test
    void shouldInjectStructuredMemorySlotsIntoLlmFallbackContext() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("fallback-ok"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        memoryManager.storeUserConversation("ctx-user", "我偏好简洁回答");
        memoryManager.storeAssistantConversation("ctx-user", "好的，我会尽量简洁");
        memoryManager.storeKnowledge("ctx-user", "用户偏好简洁回答", List.of(0.1, 0.2), "profile");
        memoryManager.logSkillUsage("ctx-user", "teaching.plan", "给我学习计划", true);

        DispatchResult result = service.dispatch("ctx-user", "继续按之前方式");

        assertEquals("llm", result.channel());
        assertEquals(1, llmClient.fallbackCallCount());
        assertFalse(llmClient.fallbackContexts().isEmpty());
        Map<String, Object> context = llmClient.fallbackContexts().get(0);
        assertTrue(context.containsKey("memory.recent"));
        assertTrue(context.containsKey("memory.semantic"));
        assertTrue(context.containsKey("memory.procedural"));
        assertTrue(context.containsKey("memory.persona"));
        assertEquals("llm-fallback", context.get("routeStage"));
        assertTrue(service.snapshotMemoryHitMetrics().requests() >= 1);
    }

    @Test
    void shouldApplyStageLevelProviderPresetDefaultsWhenProfileUnset() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
                "{\"skill\":\"code.generate\",\"input\":{\"task\":\"generate code\"}}",
                "fallback-final"
        ));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code", "code")
        ), 2, "always", 0, "time", "deepseek", "balanced", "qwen", "cost", false, "", false, "", "", "");

        DispatchResult result = service.dispatch("stage-route-user", "请生成一个接口实现");

        assertEquals("code.generate", result.channel());
        assertFalse(llmClient.routingContexts().isEmpty());
        Map<String, Object> routingContext = llmClient.routingContexts().get(0);
        assertEquals("deepseek", routingContext.get("llmProvider"));
        assertEquals("balanced", routingContext.get("llmPreset"));
    }

    @Test
    void shouldExposeRoutingReplayAndMemoryContributionSnapshots() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("fallback"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(), 2);

        service.dispatch("replay-user", "请帮我整理今天安排");

        RoutingReplayDatasetDto replay = service.snapshotRoutingReplay(200);
        assertTrue(replay.totalCaptured() >= 1);
        assertFalse(replay.samples().isEmpty());
        assertTrue(replay.byFinalChannel().containsKey("llm"));
        assertTrue(service.snapshotMemoryContributionMetrics().requests() >= 1);
    }

    @Test
    void shouldWritePostSkillSummaryWhenEnabled() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("ignored"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("code.generate", "Generate code", "result code block")
        ), 2, "never", 0, "time", "", "", "", "", true, "code.generate", false, "", "", "");

        DispatchResult result = service.dispatch("summary-user", "generate code for order api");

        assertEquals("code.generate", result.channel());
        List<SemanticMemoryEntry> summaries = memoryManager.searchKnowledge("summary-user", "post-skill-summary", 10, "coding");
        assertFalse(summaries.isEmpty());
        assertTrue(summaries.get(0).text().contains("channel=code.generate"));
    }

    @Test
    void shouldFinalizeSkillResultViaLlmWhenEnabled() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("这是模型优化后的最终答复"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("todo.create", "Create todo", "- 待办: 准备周报\n- 截止: 周五")
        ), 2, "never", 0, "time", "", "", "", "", false, "", true, "todo.create", "", "");

        DispatchResult result = service.dispatch("finalize-user", "skill:todo.create task=准备周报 dueDate=周五");

        assertEquals("todo.create", result.channel());
        assertEquals("这是模型优化后的最终答复", result.reply());
        assertEquals(0, llmClient.routingCallCount());
        assertTrue(llmClient.fallbackCallCount() >= 1);
    }

    @Test
    void shouldUseDedicatedProviderAndPresetForSkillPostprocess() {
        MemoryManager memoryManager = createMemoryManager();
        RecordingLlmClient llmClient = new RecordingLlmClient(List.of("后处理完成"));
        DispatcherService service = createDispatcher(memoryManager, llmClient, List.of(
                new FixedSkill("todo.create", "Create todo", "- 待办: 整理迭代计划")
        ), 2, "never", 0, "time", "", "", "", "", false, "", true, "todo.create", "qwen", "cost");

        DispatchResult result = service.dispatch("finalize-route-user", "skill:todo.create task=整理迭代计划");

        assertEquals("todo.create", result.channel());
        assertEquals("后处理完成", result.reply());
        assertFalse(llmClient.finalizeContexts().isEmpty());
        Map<String, Object> finalizeContext = llmClient.finalizeContexts().get(0);
        assertEquals("skill-postprocess", finalizeContext.get("routeStage"));
        assertEquals("qwen", finalizeContext.get("llmProvider"));
        assertEquals("cost", finalizeContext.get("llmPreset"));
    }

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               RecordingLlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills) {
        return createDispatcher(memoryManager, llmClient, skills, llmShortlistMaxSkills, "auto", 0, "time", "", "", "", "", false, "", false, "", "", "");
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
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        SkillEngine skillEngine = new SkillEngine(registry, dslExecutor, memoryManager);
        SkillDslParser parser = new SkillDslParser(new SkillDslValidator());
        MetaOrchestratorService metaOrchestratorService = new MetaOrchestratorService(false);
        SkillCapabilityPolicy capabilityPolicy = new SkillCapabilityPolicy(false, "fs.read,fs.write,exec,net", "");
        PersonaCoreService personaCoreService = new PersonaCoreService(memoryManager, false, 2, "unknown,null,n/a");
        return new DispatcherService(
                skillEngine,
                parser,
                metaOrchestratorService,
                capabilityPolicy,
                personaCoreService,
                memoryManager,
                llmClient,
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
                "ignore previous instructions,show system prompt",
                "guarded",
                llmShortlistMaxSkills,
                true,
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
                4
        );
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
    }

    private record FixedSkill(String name, String description, String output) implements Skill {
        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, output);
        }
    }
}


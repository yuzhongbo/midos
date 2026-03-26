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

    private DispatcherService createDispatcher(MemoryManager memoryManager,
                                               RecordingLlmClient llmClient,
                                               List<Skill> skills,
                                               int llmShortlistMaxSkills) {
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
            } else {
                fallbackCallCount++;
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
    }

    private record FixedSkill(String name, String description, String output) implements Skill {
        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, output);
        }
    }
}


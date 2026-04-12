package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleParamValidatorTest {

    @Test
    void shouldAutofillFromMemoryAndCoerceNumericTypes() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new MemoryNode("entity:student:123", "entity.student", Map.of("studentId", "stu-123", "name", "Alice"), null, null));
        SimpleParamValidator validator = new SimpleParamValidator(
                registry,
                dispatcherMemoryFacade(gateway(List.of()), graphMemory)
        );

        ParamValidator.ValidationResult result = validator.validate(
                "teaching.plan",
                Map.of("topic", "数学", "durationWeeks", "12周", "weeklyHours", "9小时"),
                new DecisionOrchestrator.OrchestrationRequest("u1", "数学学习计划", new SkillContext("u1", "数学学习计划", Map.of()), Map.of())
        );

        assertTrue(result.valid());
        assertEquals(12, result.normalizedParams().get("durationWeeks"));
        assertEquals(9, result.normalizedParams().get("weeklyHours"));
        assertEquals("stu-123", result.autofilledParams().get("studentId"));
    }

    @Test
    void shouldFillTeachingPlanDefaultsWhenMemoryDoesNotContainNumbers() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        SimpleParamValidator validator = new SimpleParamValidator(
                registry,
                dispatcherMemoryFacade(gateway(List.of(ConversationTurn.user("帮我做个英语学习计划"))), new GraphMemory())
        );

        ParamValidator.ValidationResult result = validator.validate(
                "teaching.plan",
                Map.of("topic", "英语"),
                new DecisionOrchestrator.OrchestrationRequest("u1", "英语学习计划", new SkillContext("u1", "英语学习计划", Map.of()), Map.of())
        );

        assertTrue(result.valid());
        assertEquals(4, result.normalizedParams().get("durationWeeks"));
        assertEquals(6, result.normalizedParams().get("weeklyHours"));
    }

    @Test
    void shouldInferStudentIdFromGraphMemory() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new MemoryNode("entity:student:42", "entity.student", Map.of("studentId", "stu-42", "name", "Alice"), null, null));
        graphMemory.addNode("u1", new MemoryNode("event:lesson:latest", "event.lesson", Map.of("topic", "数学"), null, null));
        graphMemory.addNode("u1", new MemoryNode("result:plan:latest", "result.plan", Map.of("status", "draft"), null, null));
        graphMemory.addEdge("u1", new MemoryEdge("event:lesson:latest", "entity:student:42", "about-student", 0.9, Map.of(), null));
        graphMemory.addEdge("u1", new MemoryEdge("result:plan:latest", "event:lesson:latest", "derived-from", 0.8, Map.of(), null));
        SimpleParamValidator validator = new SimpleParamValidator(registry, dispatcherMemoryFacade(gateway(List.of()), graphMemory));

        ParamValidator.ValidationResult result = validator.validate(
                "teaching.plan",
                Map.of("topic", "数学"),
                new DecisionOrchestrator.OrchestrationRequest("u1", "给 Alice 做数学计划", new SkillContext("u1", "给 Alice 做数学计划", Map.of()), Map.of())
        );

        assertTrue(result.valid());
        assertEquals("stu-42", result.autofilledParams().get("studentId"));
    }

    @Test
    void shouldRejectOutOfRangeTeachingPlanNumbers() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        SimpleParamValidator validator = new SimpleParamValidator(registry);

        ParamValidator.ValidationResult result = validator.validate(
                "teaching.plan",
                Map.of("topic", "数学", "durationWeeks", 60, "weeklyHours", 100),
                new DecisionOrchestrator.OrchestrationRequest("u1", "数学学习计划", new SkillContext("u1", "数学学习计划", Map.of()), Map.of())
        );

        assertTrue(result.needsClarification());
        assertTrue(result.message().contains("durationWeeks 需在 1-52"));
        assertTrue(result.message().contains("weeklyHours 需在 1-80"));
    }

    @Test
    void shouldApplyFileSearchSchemaDefaultsAndTypeCoercion() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        SimpleParamValidator validator = new SimpleParamValidator(registry);

        ParamValidator.ValidationResult result = validator.validate(
                "file.search",
                Map.of("keyword", "dispatcher", "limit", "12"),
                new DecisionOrchestrator.OrchestrationRequest("u1", "查 dispatcher 文件", new SkillContext("u1", "查 dispatcher 文件", Map.of()), Map.of())
        );

        assertTrue(result.valid());
        assertEquals(12, result.normalizedParams().get("limit"));
    }

    private MemoryGateway gateway(List<ConversationTurn> history) {
        return new MemoryGateway() {
            @Override
            public List<ConversationTurn> recentHistory(String userId) {
                return history;
            }

            @Override
            public List<SkillUsageStats> skillUsageStats(String userId) {
                return List.of();
            }

            @Override
            public void appendUserConversation(String userId, String message) {
            }

            @Override
            public void appendAssistantConversation(String userId, String message) {
            }

            @Override
            public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
            }

            @Override
            public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, SemanticMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
            }

            @Override
            public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
                return PreferenceProfile.empty();
            }

            @Override
            public LongTask createLongTask(String userId, String title, String objective, List<String> steps, Instant dueAt, Instant nextCheckAt) {
                return null;
            }

            @Override
            public LongTask updateLongTaskProgress(String userId, String taskId, String workerId, String completedStep, String note, String blockedReason, Instant nextCheckAt, boolean markCompleted) {
                return null;
            }

            @Override
            public LongTask updateLongTaskStatus(String userId, String taskId, LongTaskStatus status, String note, Instant nextCheckAt) {
                return null;
            }
        };
    }

    private DispatcherMemoryFacade dispatcherMemoryFacade(MemoryGateway memoryGateway, GraphMemory graphMemory) {
        return new DispatcherMemoryFacade(new MemoryFacade(graphMemory, null), memoryGateway, graphMemory, null);
    }
}

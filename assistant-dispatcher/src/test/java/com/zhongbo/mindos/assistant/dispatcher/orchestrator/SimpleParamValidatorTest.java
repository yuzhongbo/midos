package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
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
        SimpleParamValidator validator = new SimpleParamValidator(
                registry,
                gateway(List.of(ConversationTurn.user("给 stu-123 制定数学学习计划，每周 8 小时，持续 6 周")))
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
                gateway(List.of(ConversationTurn.user("帮我做个英语学习计划")))
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
}

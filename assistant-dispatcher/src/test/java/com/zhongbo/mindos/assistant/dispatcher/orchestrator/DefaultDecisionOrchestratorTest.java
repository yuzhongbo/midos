package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDecisionOrchestratorTest {

    private DefaultDecisionOrchestrator orchestrator(SkillEngine skillEngine, boolean parallelMcp) {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        return new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, noopGateway(), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(registry, noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                skillEngine,
                noopRecorder(),
                new TaskExecutor(3),
                parallelMcp,
                500,
                0,
                "",
                3
        );
    }

    private DecisionOrchestrator.OrchestrationRequest request() {
        return new DecisionOrchestrator.OrchestrationRequest("user", "input", new SkillContext("user", "input", Map.of()), Map.of());
    }

    @Test
    void shouldClarifyWhenSchemaMissing() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        assertTrue(registry.find("teaching.plan").isPresent());
        ParamValidator validator = new SimpleParamValidator(registry, noopGateway());
        ParamValidator.ValidationResult validation = validator.validate("teaching.plan", Map.of());
        assertFalse(validation.valid());
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(simpleSkillEngine(Map.of()), noopGateway(), 3, 0.40, 0.35, 0.15, 0.10),
                validator,
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                simpleSkillEngine(Map.of()),
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );
        Decision decision = new Decision("plan", "teaching.plan", Map.of(), 0.72, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                decision,
                new DecisionOrchestrator.OrchestrationRequest("user", "", new SkillContext("user", "", Map.of()), Map.of())
        );

        assertTrue(outcome.hasClarification());
        assertEquals("semantic.clarify", outcome.clarification().skillName());
    }

    @Test
    void shouldRejectInvalidMcpNamespace() {
        DefaultDecisionOrchestrator orchestrator = orchestrator(simpleSkillEngine(Map.of()), false);
        Decision decision = new Decision("tool", "mcp.invalid", Map.of("input", "hi"), 0.80, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasClarification());
        assertTrue(outcome.clarification().output().contains("MCP"));
    }

    @Test
    void shouldReturnDslWhenParamsPresent() {
        SkillEngine skillEngine = simpleSkillEngine(Map.of("todo.create", SkillResult.success("todo.create", "ok")));
        DefaultDecisionOrchestrator orchestrator = orchestrator(skillEngine, false);
        Decision decision = new Decision("todo", "todo.create", Map.of("task", "demo task"), 0.9, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasSkillDsl());
        assertTrue(outcome.hasResult());
        assertEquals("todo.create", outcome.skillDsl().skill());
        assertEquals("todo.create", outcome.result().skillName());
    }

    @Test
    void shouldPickPreferredMcpInParallel() {
        SkillEngine skillEngine = simpleSkillEngine(Map.of(
                "mcp.a.tool", SkillResult.success("mcp.a.tool", "a"),
                "mcp.b.tool", SkillResult.success("mcp.b.tool", "b")
        ));
        DefaultDecisionOrchestrator orchestrator = orchestrator(skillEngine, true);
        Decision decision = new Decision("tool", "mcp.a.tool", Map.of("input", "demo"), 0.9, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasResult());
        assertEquals("mcp.a.tool", outcome.result().skillName());
    }

    @Test
    void shouldRetryAfterParamRepair() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        registry.register("custom.plan", ParamSchema.of(Set.of(), Set.of()));
        ParamValidator validator = new SimpleParamValidator(registry, gatewayWithHistory(List.of(
                com.zhongbo.mindos.assistant.memory.model.ConversationTurn.user("student is stu-42")
        )));
        Skill retryingSkill = new Skill() {
            private int calls;

            @Override
            public String name() {
                return "custom.plan";
            }

            @Override
            public String description() {
                return "custom.plan";
            }

            @Override
            public SkillResult run(SkillContext context) {
                calls++;
                if (context.attributes().get("studentId") == null) {
                    return SkillResult.failure(name(), "缺少必填参数: studentId");
                }
                return SkillResult.success(name(), "recovered on call " + calls);
            }

            @Override
            public int routingScore(String input) {
                return 900;
            }
        };
        SkillRegistry skillRegistry = new SkillRegistry(List.of(retryingSkill));
        SkillEngine skillEngine = new SkillEngine(skillRegistry, new SkillDslExecutor(skillRegistry));
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, gatewayWithHistory(List.of()), 3, 0.40, 0.35, 0.15, 0.10),
                validator,
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                skillEngine,
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(
                new Decision("custom", "custom.plan", Map.of(), 0.85, false),
                new DecisionOrchestrator.OrchestrationRequest("u1", "please plan", new SkillContext("u1", "please plan", Map.of()), Map.of())
        );

        assertTrue(outcome.hasResult());
        assertTrue(outcome.result().success());
        assertEquals(1, outcome.trace().replanCount());
        assertTrue(outcome.result().output().contains("recovered"));
    }

    @Test
    void shouldExecuteTaskPlanSequentially() {
        Skill fetchSkill = new Skill() {
            @Override
            public String name() {
                return "step.fetch";
            }

            @Override
            public String description() {
                return "step.fetch";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "headlines");
            }
        };
        Skill summarizeSkill = new Skill() {
            @Override
            public String name() {
                return "step.summarize";
            }

            @Override
            public String description() {
                return "step.summarize";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "summary:" + context.attributes().get("source"));
            }
        };
        SkillRegistry registry = new SkillRegistry(List.of(fetchSkill, summarizeSkill));
        SkillEngine skillEngine = new SkillEngine(registry, new SkillDslExecutor(registry));
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(skillEngine, noopGateway(), 3, 0.40, 0.35, 0.15, 0.10),
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                skillEngine,
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        Decision decision = new Decision("task", "task.plan", Map.of(
                "steps", List.of(
                        Map.of("id", "fetch", "target", "step.fetch", "params", Map.of("query", "today news"), "saveAs", "news"),
                        Map.of("id", "summarize", "target", "step.summarize", "params", Map.of("source", "${task.news.output}"), "saveAs", "summary")
                )
        ), 0.90, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

        assertTrue(outcome.hasResult());
        assertEquals("step.summarize", outcome.result().skillName());
        assertEquals("summary:headlines", outcome.result().output());
        assertEquals(2, outcome.trace().steps().size());
    }

    @Test
    void shouldExecuteViaConvenienceEntrypoint() {
        SkillEngine skillEngine = simpleSkillEngine(Map.of("todo.create", SkillResult.success("todo.create", "created")));
        DefaultDecisionOrchestrator orchestrator = orchestrator(skillEngine, false);

        SkillResult result = orchestrator.execute("创建待办", "todo.create", Map.of("task", "demo"));

        assertTrue(result.success());
        assertEquals("todo.create", result.skillName());
        assertEquals("created", result.output());
    }

    @Test
    void shouldReturnUnifiedFailurePayloadWhenAllCandidatesFail() {
        SkillEngine skillEngine = simpleSkillEngine(Map.of(
                "todo.create", SkillResult.failure("todo.create", "primary failed"),
                "todo.create.backup", SkillResult.failure("todo.create.backup", "backup failed")
        ));
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new CandidatePlanner() {
                    @Override
                    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
                        return List.of(
                                new ScoredCandidate("todo.create", 0.9, 0.8, 0.0, 0.5, List.of("primary")),
                                new ScoredCandidate("todo.create.backup", 0.8, 0.7, 0.0, 0.5, List.of("fallback"))
                        );
                    }
                },
                new SimpleParamValidator(new InMemoryParamSchemaRegistry(), noopGateway()),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                skillEngine,
                noopRecorder(),
                new TaskExecutor(3),
                false,
                500,
                0,
                "",
                3
        );

        SkillResult result = orchestrator.execute("创建待办", "todo.create", Map.of("task", "demo"));

        assertFalse(result.success());
        assertEquals("decision.orchestrator", result.skillName());
        assertTrue(result.output().contains("\"status\":\"failed\""));
        assertTrue(result.output().contains("\"intent\":\"todo.create\""));
        assertTrue(result.output().contains("\"attemptedCandidates\""));
    }

    private SkillEngine simpleSkillEngine(Map<String, SkillResult> results) {
        List<Skill> skills = results.entrySet().stream()
                .map(entry -> (Skill) new Skill() {
                    @Override
                    public String name() {
                        return entry.getKey();
                    }

                    @Override
                    public String description() {
                        return "stub skill";
                    }

                    @Override
                    public SkillResult run(SkillContext context) {
                        return entry.getValue();
                    }

                    @Override
                    public int routingScore(String input) {
                        return 900;
                    }
                }).toList();
        SkillRegistry registry = new SkillRegistry(skills);
        SkillDslExecutor executor = new SkillDslExecutor(registry);
        return new SkillEngine(registry, executor);
    }

    private PostExecutionMemoryRecorder noopRecorder() {
        return new PostExecutionMemoryRecorder(noopGateway(), false, false, "", 280);
    }

    private MemoryGateway noopGateway() {
        return new MemoryGateway() {
            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> recentHistory(String userId) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.SkillUsageStats> skillUsageStats(String userId) {
                return java.util.List.of();
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
            public void writeProcedural(String userId, com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, String text, java.util.List<Double> embedding, String bucket) {
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.PreferenceProfile updatePreferenceProfile(
                    String userId,
                    com.zhongbo.mindos.assistant.memory.model.PreferenceProfile profile) {
                return com.zhongbo.mindos.assistant.memory.model.PreferenceProfile.empty();
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask createLongTask(
                    String userId,
                    String title,
                    String objective,
                    java.util.List<String> steps,
                    java.time.Instant dueAt,
                    java.time.Instant nextCheckAt) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskProgress(
                    String userId,
                    String taskId,
                    String workerId,
                    String completedStep,
                    String note,
                    String blockedReason,
                    java.time.Instant nextCheckAt,
                    boolean markCompleted) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskStatus(
                    String userId,
                    String taskId,
                    com.zhongbo.mindos.assistant.memory.model.LongTaskStatus status,
                    String note,
                    java.time.Instant nextCheckAt) {
                return null;
            }
        };
    }

    private MemoryGateway gatewayWithHistory(java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> history) {
        return new MemoryGateway() {
            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> recentHistory(String userId) {
                return history;
            }

            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.SkillUsageStats> skillUsageStats(String userId) {
                return java.util.List.of();
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
            public void writeProcedural(String userId, com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, String text, java.util.List<Double> embedding, String bucket) {
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.PreferenceProfile updatePreferenceProfile(String userId, com.zhongbo.mindos.assistant.memory.model.PreferenceProfile profile) {
                return com.zhongbo.mindos.assistant.memory.model.PreferenceProfile.empty();
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask createLongTask(String userId, String title, String objective, java.util.List<String> steps, java.time.Instant dueAt, java.time.Instant nextCheckAt) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskProgress(String userId, String taskId, String workerId, String completedStep, String note, String blockedReason, java.time.Instant nextCheckAt, boolean markCompleted) {
                return null;
            }

            @Override
            public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskStatus(String userId, String taskId, com.zhongbo.mindos.assistant.memory.model.LongTaskStatus status, String note, java.time.Instant nextCheckAt) {
                return null;
            }
        };
    }
}

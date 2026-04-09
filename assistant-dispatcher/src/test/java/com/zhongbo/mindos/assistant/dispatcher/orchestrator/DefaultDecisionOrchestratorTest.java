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

import static org.junit.jupiter.api.Assertions.*;

class DefaultDecisionOrchestratorTest {

    private DefaultDecisionOrchestrator orchestrator(SkillEngine skillEngine, boolean parallelMcp) {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        return new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(),
                new SimpleParamValidator(registry),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                skillEngine,
                noopRecorder(),
                parallelMcp,
                500,
                ""
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
        ParamValidator validator = new SimpleParamValidator(registry);
        ParamValidator.ValidationResult validation = validator.validate("teaching.plan", Map.of());
        assertFalse(validation.valid());
        DefaultDecisionOrchestrator orchestrator = new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(),
                validator,
                new SimpleConversationLoop(),
                new SimpleFallbackPlan(),
                simpleSkillEngine(Map.of()),
                noopRecorder(),
                false,
                500,
                ""
        );
        Decision decision = new Decision("plan", "teaching.plan", Map.of(), 0.72, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, request());

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
        return new PostExecutionMemoryRecorder(new MemoryGateway() {
            @Override
            public java.util.List<com.zhongbo.mindos.assistant.memory.model.ConversationTurn> recentHistory(String userId) {
                return java.util.List.of();
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
        }, false, false, "", 280);
    }
}

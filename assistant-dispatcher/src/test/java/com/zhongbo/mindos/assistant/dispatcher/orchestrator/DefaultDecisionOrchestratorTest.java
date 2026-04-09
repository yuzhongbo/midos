package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDecisionOrchestratorTest {

    private DefaultDecisionOrchestrator orchestrator() {
        InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
        registry.registerDefaults();
        return new DefaultDecisionOrchestrator(
                new SimpleCandidatePlanner(),
                new SimpleParamValidator(registry),
                new SimpleConversationLoop(),
                new SimpleFallbackPlan()
        );
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
                new SimpleFallbackPlan()
        );
        Decision decision = new Decision("plan", "teaching.plan", Map.of(), 0.72, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, Map.of());

        assertTrue(outcome.hasClarification());
        assertEquals("semantic.clarify", outcome.clarification().skillName());
    }

    @Test
    void shouldRejectInvalidMcpNamespace() {
        DefaultDecisionOrchestrator orchestrator = orchestrator();
        Decision decision = new Decision("tool", "mcp.invalid", Map.of("input", "hi"), 0.80, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, Map.of());

        assertTrue(outcome.hasClarification());
        assertTrue(outcome.clarification().output().contains("MCP"));
    }

    @Test
    void shouldReturnDslWhenParamsPresent() {
        DefaultDecisionOrchestrator orchestrator = orchestrator();
        Decision decision = new Decision("todo", "todo.create", Map.of("task", "demo task"), 0.9, false);

        DecisionOrchestrator.OrchestrationOutcome outcome = orchestrator.orchestrate(decision, Map.of());

        assertTrue(outcome.hasSkillDsl());
        assertEquals("todo.create", outcome.skillDsl().skill());
    }
}

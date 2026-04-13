package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivilizationEvolutionEngineTest {

    @Test
    void shouldDeactivateLowPerformingOrganization() {
        CivilizationFactory factory = new CivilizationFactory();
        CivilizationUnit weak = factory.createUnit(
                "weak-org",
                "Weak Org",
                "lean",
                List.of("balanced-planner"),
                new CapabilityProfile(List.of("maintenance"), List.of("balanced"), 0.45, 0.40, 0.35, 1.1, 1.1, Map.of()),
                40.0,
                0.20,
                Map.of(
                        ResourceType.COMPUTE, 20.0,
                        ResourceType.MEMORY, 20.0,
                        ResourceType.TOOL_USAGE, 20.0,
                        ResourceType.AGENT_TIME, 20.0,
                        ResourceType.TASK_PRIORITY, 20.0
                )
        );
        CivilizationUnit strongA = factory.createUnit(
                "strong-a",
                "Strong A",
                "balanced",
                List.of("balanced-planner"),
                new CapabilityProfile(List.of("delivery"), List.of("balanced"), 0.7, 0.7, 0.7, 1.0, 1.0, Map.of()),
                100.0,
                0.70,
                Map.of(
                        ResourceType.COMPUTE, 50.0,
                        ResourceType.MEMORY, 50.0,
                        ResourceType.TOOL_USAGE, 50.0,
                        ResourceType.AGENT_TIME, 50.0,
                        ResourceType.TASK_PRIORITY, 50.0
                )
        );
        CivilizationUnit strongB = factory.createUnit(
                "strong-b",
                "Strong B",
                "balanced",
                List.of("balanced-planner"),
                new CapabilityProfile(List.of("planning"), List.of("balanced"), 0.68, 0.72, 0.69, 1.0, 1.0, Map.of()),
                100.0,
                0.72,
                Map.of(
                        ResourceType.COMPUTE, 50.0,
                        ResourceType.MEMORY, 50.0,
                        ResourceType.TOOL_USAGE, 50.0,
                        ResourceType.AGENT_TIME, 50.0,
                        ResourceType.TASK_PRIORITY, 50.0
                )
        );
        RuleSystem ruleSystem = new RuleSystem();
        ResourceSystem resourceSystem = new ResourceSystem();
        EconomicSystem economicSystem = new EconomicSystem(resourceSystem, ruleSystem);
        DigitalCivilization civilization = new DigitalCivilization(
                "Test Civilization",
                List.of(weak, strongA, strongB),
                economicSystem,
                ruleSystem,
                resourceSystem,
                1,
                Map.of()
        );
        CivilizationState state = new CivilizationState(civilization, new CivilizationMemory());

        new CivilizationEvolutionEngine(factory).evolve(state);

        CivilizationUnit updatedWeak = state.civilization().unit("weak-org").orElseThrow();
        assertFalse(updatedWeak.active());
        assertTrue(state.civilization().epoch() > civilization.epoch());
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivilizationSchedulerTest {

    @Test
    void shouldAllocateTaskThroughMarketCompetition() {
        RuleSystem ruleSystem = new RuleSystem();
        ResourceSystem resourceSystem = new ResourceSystem();
        EconomicSystem economicSystem = new EconomicSystem(resourceSystem, ruleSystem);
        CivilizationFactory factory = new CivilizationFactory();
        DigitalCivilization civilization = factory.bootstrap(economicSystem, ruleSystem, resourceSystem, List.of("balanced-planner", "conservative-planner", "aggressive-planner"));
        CivilizationScheduler scheduler = new CivilizationScheduler(
                new AIOrganizationMarket(),
                economicSystem,
                ruleSystem,
                new ReputationSystem()
        );

        CivilizationAssignment assignment = scheduler.distribute(
                Goal.of("repair memory stability", 0.7),
                civilization,
                new AutonomousPlanningContext("u1", "repair memory stability", Map.of(), null, 1, null, null, List.of())
        );

        assertTrue(assignment.assigned());
        assertEquals("atlas-org", assignment.selectedOrgId());
        assertNotNull(assignment.transaction());
        assertTrue(assignment.transaction().settled());
        assertTrue(assignment.offers().size() >= 3);
    }
}

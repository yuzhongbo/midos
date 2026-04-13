package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.DefaultEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.EvaluationDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.ExecutionDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.KpiSystem;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgDecisionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgRestructuringEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.PlanningDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.StrategyDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PlanEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PlanScore;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PredictionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.StrategyEvolutionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldModel;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalCivilizationRuntimeTest {

    @Test
    void shouldExecuteThroughCivilizationMarketAndSettlement() {
        WorldMemory worldMemory = new WorldMemory();
        StrategyEvolutionEngine evolutionEngine = new StrategyEvolutionEngine();
        MultiAgentCoordinator coordinator = new MultiAgentCoordinator(List.of(), new WorldModel(null, worldMemory), new PlanEvaluator(), evolutionEngine) {
            @Override
            public PlanSelection selectBestPlan(Goal goal, AutonomousPlanningContext context, List<String> allowedAgentIds) {
                return new PlanSelection(
                        "conservative-planner",
                        "conservative",
                        new TaskGraph(List.of(
                                new TaskNode("task-1", "code.generate", Map.of("plannerAgentId", "conservative-planner", "plannerStrategyType", "conservative"), List.of(), "result", false, 1)
                        )),
                        new PredictionResult(0.88, 0.25, 0.25, 0.10),
                        new PlanScore(0.82, 0.85),
                        List.of(),
                        "selected"
                );
            }
        };
        AutonomousGraphExecutor executor = new AutonomousGraphExecutor(null, 2500) {
            @Override
            public GoalExecutionResult execute(Goal goal, TaskGraph graph, AutonomousPlanningContext context) {
                SkillResult result = SkillResult.success("code.generate", "delivered");
                return new GoalExecutionResult(
                        goal,
                        graph,
                        new TaskGraphExecutionResult(
                                result,
                                List.of(new TaskGraphExecutionResult.NodeResult("task-1", "code.generate", "success", result, false, 1)),
                                Map.of(),
                                List.of("task-1")
                        ),
                        result,
                        context.userId(),
                        goal.description(),
                        context.iteration(),
                        Instant.now(),
                        Instant.now()
                );
            }
        };
        RuleSystem ruleSystem = new RuleSystem();
        ResourceSystem resourceSystem = new ResourceSystem();
        EconomicSystem economicSystem = new EconomicSystem(resourceSystem, ruleSystem);
        CivilizationFactory factory = new CivilizationFactory();
        DigitalCivilizationRuntime runtime = new DigitalCivilizationRuntime(
                new StrategyDepartmentService(),
                new PlanningDepartmentService(coordinator),
                new ExecutionDepartmentService(executor),
                new EvaluationDepartmentService(new DefaultEvaluator(), new KpiSystem(), worldMemory, evolutionEngine),
                new OrgDecisionEngine(),
                new OrgRestructuringEngine(),
                new CivilizationScheduler(new AIOrganizationMarket(), economicSystem, ruleSystem, new ReputationSystem()),
                new CivilizationEvolutionEngine(factory),
                new CivilizationMemory(),
                new ReputationSystem(),
                factory,
                economicSystem,
                ruleSystem,
                resourceSystem,
                List.of()
        );

        CivilizationCycleResult result = runtime.runCycle(
                Goal.of("repair memory stability", 0.8),
                new AutonomousPlanningContext("u-civ", "repair memory stability", Map.of(), null, 1, null, null, List.of())
        );

        assertTrue(result.assigned());
        assertEquals("atlas-org", result.selectedOrgId());
        assertNotNull(result.organizationCycle());
        assertNotNull(result.trace());
        assertTrue(result.assignment().transaction().settled());
        assertTrue(result.civilizationAfter().organizations().size() >= 3);
        CivilizationUnit before = result.civilizationBefore().unit(result.selectedOrgId()).orElseThrow();
        CivilizationUnit after = result.civilizationAfter().unit(result.selectedOrgId()).orElseThrow();
        assertTrue(after.budget().credits() > before.budget().credits());
        assertTrue(after.reputation() >= before.reputation());
    }
}

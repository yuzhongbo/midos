package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.DefaultEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
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

class AIOrganizationRuntimeTest {

    @Test
    void shouldRunOrganizationalCycleAndRestructure() {
        WorldMemory worldMemory = new WorldMemory();
        StrategyEvolutionEngine evolutionEngine = new StrategyEvolutionEngine();
        MultiAgentCoordinator coordinator = new MultiAgentCoordinator(List.of(), new WorldModel(null, worldMemory), new PlanEvaluator(), evolutionEngine) {
            @Override
            public PlanSelection selectBestPlan(Goal goal, AutonomousPlanningContext context, List<String> allowedAgentIds) {
                return new PlanSelection(
                        "aggressive-planner",
                        "aggressive",
                        new TaskGraph(List.of(
                                new TaskNode("task-1", "code.generate", Map.of("plannerAgentId", "aggressive-planner", "plannerStrategyType", "aggressive"), List.of(), "result", false, 1)
                        )),
                        new PredictionResult(0.72, 0.22, 0.25, 0.35),
                        new PlanScore(0.70, 0.78),
                        List.of(),
                        "selected"
                );
            }
        };
        AutonomousGraphExecutor executor = new AutonomousGraphExecutor(null, 2500) {
            @Override
            public GoalExecutionResult execute(Goal goal, TaskGraph graph, AutonomousPlanningContext context) {
                SkillResult result = SkillResult.failure("code.generate", "generation stalled");
                return new GoalExecutionResult(
                        goal,
                        graph,
                        new TaskGraphExecutionResult(
                                result,
                                List.of(new TaskGraphExecutionResult.NodeResult("task-1", "code.generate", "failed", result, false, 1)),
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
        KpiSystem kpiSystem = new KpiSystem() {
            @Override
            public KPI measure(Goal goal,
                               PlanningOutcome planningOutcome,
                               GoalExecutionResult executionResult,
                               com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult evaluation,
                               AIOrganization organization,
                               OrgMemory orgMemory) {
                return new KPI(0.30, 0.55, 0.20, 0.25);
            }
        };
        AIOrganizationRuntime runtime = new AIOrganizationRuntime(
                new StrategyDepartmentService(),
                new PlanningDepartmentService(coordinator),
                new ExecutionDepartmentService(executor),
                new EvaluationDepartmentService(new DefaultEvaluator(), kpiSystem, worldMemory, evolutionEngine),
                new OrgDecisionEngine(),
                new OrgRestructuringEngine(),
                new OrgMemory(),
                List.of()
        );

        OrganizationCycleResult result = runtime.runCycle(
                Goal.of("组织级交付目标", 0.9),
                new AutonomousPlanningContext("u-org", "组织级交付目标", Map.of(), null, 1, null, null, List.of())
        );

        assertTrue(result.hasPlan());
        assertNotNull(result.strategyDirective());
        assertNotNull(result.assessment());
        assertNotNull(result.assessment().worldTrace());
        assertNotNull(result.orgTrace());
        assertEquals(OrganizationDecisionType.EXPAND_PLANNING, result.decision().type());
        assertTrue(result.organizationAfter().revision() > result.organizationBefore().revision());
        assertTrue(result.organizationAfter().planningDept().intMetadata("scenarioDepth", 1) > result.organizationBefore().planningDept().intMetadata("scenarioDepth", 1));
    }
}

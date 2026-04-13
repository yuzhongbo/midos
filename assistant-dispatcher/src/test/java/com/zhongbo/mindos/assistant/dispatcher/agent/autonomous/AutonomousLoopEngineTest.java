package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganizationRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.EvaluationDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.ExecutionDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.KpiSystem;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgDecisionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory;
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
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ExecutionMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.OrchestrationExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousLoopEngineTest {

    @Test
    void shouldReplanUntilGoalCompletes() {
        Goal goal = Goal.of("修复构建并完成交付", 1.0);
        AtomicInteger selectionCalls = new AtomicInteger();
        GoalMemory goalMemory = new GoalMemory();
        WorldMemory worldMemory = new WorldMemory();
        StrategyEvolutionEngine evolutionEngine = new StrategyEvolutionEngine();
        RecordingExecutionMemoryFacade memoryFacade = new RecordingExecutionMemoryFacade();
        MultiAgentCoordinator coordinator = new MultiAgentCoordinator(List.of(), new WorldModel(null, worldMemory), new PlanEvaluator(), evolutionEngine) {
            @Override
            public PlanSelection selectBestPlan(Goal goal, AutonomousPlanningContext context, List<String> allowedAgentIds) {
                int call = selectionCalls.incrementAndGet();
                TaskGraph graph = call == 1 ? failingGraph() : successfulGraph();
                String agentId = call == 1 ? "aggressive-planner" : "conservative-planner";
                String strategyType = call == 1 ? "aggressive" : "conservative";
                return new PlanSelection(
                        agentId,
                        strategyType,
                        graph,
                        call == 1 ? new PredictionResult(0.72, 0.20, 0.20, 0.35) : new PredictionResult(0.84, 0.55, 0.55, 0.12),
                        call == 1 ? new PlanScore(0.71, 0.82) : new PlanScore(0.78, 0.45),
                        List.of(),
                        "selected"
                );
            }
        };
        AutonomousGraphExecutor executor = new AutonomousGraphExecutor(null, 2500) {
            @Override
            public GoalExecutionResult execute(Goal goal, TaskGraph graph, AutonomousPlanningContext context) {
                boolean success = graph.nodes().stream().anyMatch(node -> "code.generate".equals(node.target()));
                SkillResult result = success
                        ? SkillResult.success("code.generate", "delivery complete")
                        : SkillResult.failure("file.search", "missing project file");
                TaskGraphExecutionResult graphResult = new TaskGraphExecutionResult(
                        result,
                        List.of(new TaskGraphExecutionResult.NodeResult(
                                graph.nodes().get(0).id(),
                                graph.nodes().get(0).target(),
                                success ? "success" : "failed",
                                result,
                                false,
                                1
                        )),
                        Map.of(),
                        List.of(graph.nodes().get(0).id())
                );
                return new GoalExecutionResult(
                        goal,
                        graph,
                        graphResult,
                        result,
                        context.userId(),
                        goal.description(),
                        context.iteration(),
                        Instant.now(),
                        Instant.now()
                );
            }
        };
        AIOrganizationRuntime organizationRuntime = new AIOrganizationRuntime(
                new StrategyDepartmentService(),
                new PlanningDepartmentService(coordinator),
                new ExecutionDepartmentService(executor),
                new EvaluationDepartmentService(new DefaultEvaluator(), new KpiSystem(), worldMemory, evolutionEngine),
                new OrgDecisionEngine(),
                new OrgRestructuringEngine(),
                new OrgMemory(),
                List.of()
        );

        AutonomousLoopEngine engine = new AutonomousLoopEngine(
                organizationRuntime,
                goalMemory,
                memoryFacade,
                3
        );

        AutonomousGoalRunResult runResult = engine.run(goal, "u1", Map.of("mode", "test"));

        assertTrue(runResult.success());
        assertEquals(GoalStatus.COMPLETED, runResult.goal().status());
        assertEquals(2, runResult.cycleCount());
        assertEquals(2, selectionCalls.get());
        assertEquals(2, goalMemory.iterationCount(goal.goalId()));
        assertEquals(2, memoryFacade.recordedResults.size());
        assertTrue(goalMemory.failedTargets(goal.goalId()).contains("file.search"));
        assertEquals(2, runResult.worldTraces().size());
        assertEquals(2, runResult.orgTraces().size());
        assertTrue(runResult.organization() != null && runResult.organization().revision() >= 2);
        assertTrue(evolutionEngine.weightOf("conservative-planner") > evolutionEngine.weightOf("aggressive-planner"));
    }

    private TaskGraph failingGraph() {
        return new TaskGraph(List.of(
                new TaskNode("goal-task-1", "file.search", Map.of("query", "missing file"), List.of(), "search", false, 1)
        ));
    }

    private TaskGraph successfulGraph() {
        return new TaskGraph(List.of(
                new TaskNode("goal-task-1", "code.generate", Map.of("task", "delivery"), List.of(), "delivery", false, 1)
        ));
    }

    private static final class RecordingExecutionMemoryFacade implements ExecutionMemoryFacade {
        private final List<SkillResult> recordedResults = new ArrayList<>();

        @Override
        public void record(OrchestrationExecutionResult result) {
        }

        @Override
        public void record(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
            if (result != null) {
                recordedResults.add(result);
            }
        }

        @Override
        public void commit(String userId, MemoryWriteBatch batch) {
        }
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIRuntimeKernel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitivePluginRegistry;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.MemoryCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.PlanningCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.PredictionCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ReasoningCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeOptimizer;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeScheduler;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeStateStore;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ToolUseCognitivePlugin;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousLoopEngineTest {

    @Test
    void shouldReplanUntilGoalCompletesThroughKernel() {
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

        AGIRuntimeKernel kernel = buildKernel(coordinator, executor, worldMemory, evolutionEngine);
        AutonomousLoopEngine engine = new AutonomousLoopEngine(kernel, goalMemory, memoryFacade, 3);

        AutonomousGoalRunResult runResult = engine.run(goal, "u1", Map.of("mode", "test"));

        assertTrue(runResult.success());
        assertEquals(GoalStatus.COMPLETED, runResult.goal().status());
        assertEquals(2, runResult.cycleCount());
        assertEquals(2, selectionCalls.get());
        assertEquals(2, goalMemory.iterationCount(goal.goalId()));
        assertEquals(2, memoryFacade.recordedResults.size());
        assertTrue(goalMemory.failedTargets(goal.goalId()).contains("file.search"));
        assertNotNull(runResult.runtimeState());
        assertNotNull(runResult.runtimeHistory());
        assertEquals(TaskState.COMPLETED, runResult.runtimeState().state());
        assertTrue(runResult.runtimeHistory().cycleCount() >= 4);
        assertTrue(runResult.organization() == null);
        assertTrue(runResult.civilization() == null);
    }

    private AGIRuntimeKernel buildKernel(MultiAgentCoordinator coordinator,
                                         AutonomousGraphExecutor executor,
                                         WorldMemory worldMemory,
                                         StrategyEvolutionEngine evolutionEngine) {
        CognitivePluginRegistry registry = new CognitivePluginRegistry(List.of(
                new PlanningCognitivePlugin(coordinator),
                new PredictionCognitivePlugin(new WorldModel(null, worldMemory)),
                new MemoryCognitivePlugin(),
                new ReasoningCognitivePlugin(),
                new ToolUseCognitivePlugin(executor)
        ));
        RuntimeOptimizer optimizer = new RuntimeOptimizer();
        AGIMemory memory = new AGIMemory();
        RuntimeStateStore stateStore = new RuntimeStateStore();
        RuntimeScheduler scheduler = new RuntimeScheduler(registry, memory, optimizer);
        ExecutionEngine executionEngine = new ExecutionEngine(executor, new DefaultEvaluator());
        return new AGIRuntimeKernel(scheduler, executionEngine, stateStore, memory, optimizer);
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

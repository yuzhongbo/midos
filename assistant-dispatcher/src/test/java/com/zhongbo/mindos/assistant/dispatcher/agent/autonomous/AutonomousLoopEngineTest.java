package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.FinalPlanner;
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
        AtomicInteger planCalls = new AtomicInteger();
        GoalMemory goalMemory = new GoalMemory();
        RecordingExecutionMemoryFacade memoryFacade = new RecordingExecutionMemoryFacade();
        AutonomousPlanner planner = new AutonomousPlanner((FinalPlanner) null) {
            @Override
            public TaskGraph plan(Goal goal, AutonomousPlanningContext context) {
                planCalls.incrementAndGet();
                return failingGraph();
            }

            @Override
            public TaskGraph replan(Goal goal,
                                    GoalExecutionResult result,
                                    EvaluationResult evaluation,
                                    AutonomousPlanningContext context) {
                planCalls.incrementAndGet();
                return successfulGraph();
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

        AutonomousLoopEngine engine = new AutonomousLoopEngine(
                planner,
                executor,
                new DefaultEvaluator(),
                goalMemory,
                memoryFacade,
                3
        );

        AutonomousGoalRunResult runResult = engine.run(goal, "u1", Map.of("mode", "test"));

        assertTrue(runResult.success());
        assertEquals(GoalStatus.COMPLETED, runResult.goal().status());
        assertEquals(2, runResult.cycleCount());
        assertEquals(2, planCalls.get());
        assertEquals(2, goalMemory.iterationCount(goal.goalId()));
        assertEquals(2, memoryFacade.recordedResults.size());
        assertTrue(goalMemory.failedTargets(goal.goalId()).contains("file.search"));
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

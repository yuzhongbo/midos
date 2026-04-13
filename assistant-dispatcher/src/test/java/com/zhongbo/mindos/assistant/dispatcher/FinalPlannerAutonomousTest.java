package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalStatus;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalPlannerAutonomousTest {

    @Test
    void shouldBuildTaskGraphFromGoalClauses() {
        FinalPlanner planner = new FinalPlanner();
        Goal goal = Goal.of("写代码 然后 创建待办", 1.0);

        TaskGraph graph = planner.plan(
                goal,
                new AutonomousPlanningContext("u1", goal.description(), Map.of(), new GoalMemory(), 1, null, null, List.of())
        );

        assertEquals(2, graph.nodes().size());
        assertEquals(List.of("code.generate", "todo.create"), graph.nodes().stream().map(node -> node.target()).toList());
        assertEquals(List.of(), graph.nodes().get(0).dependsOn());
        assertEquals(List.of("goal-task-1"), graph.nodes().get(1).dependsOn());
    }

    @Test
    void shouldExcludeFailedTargetsDuringGoalReplan() {
        FinalPlanner planner = new FinalPlanner();
        Goal goal = Goal.of("写代码 然后 创建待办", 1.0);
        GoalMemory goalMemory = new GoalMemory();
        AutonomousPlanningContext context = new AutonomousPlanningContext("u1", goal.description(), Map.of(), goalMemory, 1, null, null, List.of());
        TaskGraph initial = planner.plan(goal, context);
        GoalExecutionResult failedResult = new GoalExecutionResult(
                goal.markInProgress(),
                initial,
                new TaskGraphExecutionResult(
                        SkillResult.failure("code.generate", "compile failed"),
                        List.of(new TaskGraphExecutionResult.NodeResult("goal-task-1", "code.generate", "failed", SkillResult.failure("code.generate", "compile failed"), false, 1)),
                        Map.of(),
                        List.of("goal-task-1")
                ),
                SkillResult.failure("code.generate", "compile failed"),
                "u1",
                goal.description(),
                1,
                Instant.now(),
                Instant.now()
        );
        EvaluationResult evaluation = new EvaluationResult(
                goal.goalId(),
                GoalStatus.IN_PROGRESS,
                false,
                false,
                true,
                "compile failed",
                List.of(),
                List.of("goal-task-1", "goal-task-2"),
                List.of("code.generate"),
                0.0,
                Instant.now()
        );

        TaskGraph replanned = planner.replan(goal, failedResult, evaluation, context.nextIteration(failedResult, evaluation, evaluation.failedTargets()));

        assertEquals(1, replanned.nodes().size());
        assertEquals("todo.create", replanned.nodes().get(0).target());
        assertEquals(List.of(), replanned.nodes().get(0).dependsOn());
    }
}

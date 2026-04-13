package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerAgentStrategyTest {

    @Test
    void shouldProduceStrategySpecificPlans() {
        AutonomousPlanner basePlanner = new AutonomousPlanner(null, null) {
            @Override
            public TaskGraph plan(Goal goal, AutonomousPlanningContext context) {
                return baseGraph();
            }

            @Override
            public TaskGraph replan(Goal goal,
                                    com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult result,
                                    com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult evaluation,
                                    AutonomousPlanningContext context) {
                return baseGraph();
            }
        };
        Goal goal = Goal.of("写代码 然后 创建待办", 1.0);
        AutonomousPlanningContext context = AutonomousPlanningContext.empty();

        TaskGraph balanced = new BalancedPlannerAgent(basePlanner).plan(goal, context);
        TaskGraph conservative = new ConservativePlannerAgent(basePlanner).plan(goal, context);
        TaskGraph aggressive = new AggressivePlannerAgent(basePlanner).plan(goal, context);

        assertEquals(List.of("code.generate", "todo.create"), balanced.nodes().stream().map(TaskNode::target).toList());
        assertEquals("balanced", balanced.nodes().get(0).params().get("plannerStrategyType"));

        assertEquals("semantic.analyze", conservative.nodes().get(0).target());
        assertTrue(conservative.nodes().stream().filter(node -> !"semantic.analyze".equals(node.target())).allMatch(node -> node.maxAttempts() >= 3));
        assertTrue(conservative.nodes().stream().filter(node -> "code.generate".equals(node.target())).findFirst().orElseThrow().dependsOn().contains("planner-preflight"));

        assertEquals("semantic.analyze", aggressive.nodes().get(0).target());
        assertTrue(aggressive.nodes().stream().filter(node -> !"semantic.analyze".equals(node.target())).allMatch(node -> node.dependsOn().isEmpty()));
        assertTrue(aggressive.nodes().stream().allMatch(node -> node.maxAttempts() == 1));
    }

    private TaskGraph baseGraph() {
        return new TaskGraph(List.of(
                new TaskNode("goal-task-1", "code.generate", Map.of("task", "controller"), List.of(), "code", false, 2),
                new TaskNode("goal-task-2", "todo.create", Map.of("task", "ship feature"), List.of("goal-task-1"), "todo", false, 2)
        ));
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentCoordinatorTest {

    @Test
    void shouldSelectPlanWithBestPredictedScore() {
        Goal goal = Goal.of("完成交付", 1.0);
        PlannerAgent conservative = planner("conservative-planner", "conservative", graph("conservative-planner", "conservative"));
        PlannerAgent balanced = planner("balanced-planner", "balanced", graph("balanced-planner", "balanced"));
        PlannerAgent aggressive = planner("aggressive-planner", "aggressive", graph("aggressive-planner", "aggressive"));
        WorldModel worldModel = new WorldModel(null, null) {
            @Override
            public PredictionResult predict(TaskGraph graph, AutonomousPlanningContext context) {
                String agentId = String.valueOf(graph.nodes().get(0).params().get("plannerAgentId"));
                return switch (agentId) {
                    case "conservative-planner" -> new PredictionResult(0.84, 0.60, 0.70, 0.10);
                    case "aggressive-planner" -> new PredictionResult(0.72, 0.20, 0.15, 0.35);
                    default -> new PredictionResult(0.80, 0.35, 0.35, 0.15);
                };
            }
        };
        MultiAgentCoordinator coordinator = new MultiAgentCoordinator(
                List.of(conservative, aggressive, balanced),
                worldModel,
                new PlanEvaluator(),
                new StrategyEvolutionEngine()
        );

        MultiAgentCoordinator.PlanSelection selection = coordinator.selectBestPlan(goal, AutonomousPlanningContext.empty());

        assertEquals("balanced-planner", selection.agentId());
        assertEquals("balanced", selection.strategyType());
        assertEquals(3, selection.proposals().size());
        assertTrue(selection.summary().contains("winner=balanced-planner"));
    }

    private PlannerAgent planner(String agentId, String strategyType, TaskGraph graph) {
        return new PlannerAgent(agentId, strategyType, null) {
            @Override
            protected TaskGraph proposePlan(Goal goal, AutonomousPlanningContext context) {
                return graph;
            }
        };
    }

    private TaskGraph graph(String agentId, String strategyType) {
        return new TaskGraph(List.of(
                new TaskNode(
                        "task-1",
                        "code.generate",
                        Map.of("plannerAgentId", agentId, "plannerStrategyType", strategyType),
                        List.of(),
                        "result",
                        false,
                        1
                )
        ));
    }
}

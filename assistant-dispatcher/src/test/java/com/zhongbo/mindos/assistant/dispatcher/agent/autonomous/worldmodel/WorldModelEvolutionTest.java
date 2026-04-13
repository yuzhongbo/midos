package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalStatus;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldModelEvolutionTest {

    @Test
    void shouldApplyPredictionGapLearningAndUpdateWeights() {
        TaskGraph aggressiveGraph = new TaskGraph(List.of(
                new TaskNode("task-1", "code.generate", Map.of("plannerAgentId", "aggressive-planner", "plannerStrategyType", "aggressive"), List.of(), "result", false, 1)
        ));
        WorldMemory worldMemory = new WorldMemory();
        WorldModel worldModel = new WorldModel(null, worldMemory);
        PredictionResult before = worldModel.predict(aggressiveGraph);

        WorldMemory.ExecutionTrace trace = worldMemory.record(
                Goal.of("写代码", 1.0),
                new MultiAgentCoordinator.PlanSelection(
                        "aggressive-planner",
                        "aggressive",
                        aggressiveGraph,
                        new PredictionResult(0.80, 0.20, 0.20, 0.30),
                        new PlanScore(0.70, 0.80),
                        List.of(),
                        "aggressive"
                ),
                new GoalExecutionResult(
                        Goal.of("写代码", 1.0),
                        aggressiveGraph,
                        new TaskGraphExecutionResult(
                                SkillResult.failure("code.generate", "build failed"),
                                List.of(new TaskGraphExecutionResult.NodeResult("task-1", "code.generate", "failed", SkillResult.failure("code.generate", "build failed"), false, 1)),
                                Map.of(),
                                List.of("task-1")
                        ),
                        SkillResult.failure("code.generate", "build failed"),
                        "u1",
                        "写代码",
                        1,
                        Instant.now(),
                        Instant.now()
                ),
                new EvaluationResult(
                        "goal-1",
                        GoalStatus.IN_PROGRESS,
                        false,
                        false,
                        true,
                        "failed",
                        List.of(),
                        List.of("task-1"),
                        List.of("code.generate"),
                        0.10,
                        Instant.now()
                )
        );

        PredictionResult after = worldModel.predict(aggressiveGraph);
        StrategyEvolutionEngine evolutionEngine = new StrategyEvolutionEngine();
        evolutionEngine.update(List.of(trace));

        assertTrue(after.successProbability() < before.successProbability());
        assertTrue(evolutionEngine.weightOf("aggressive-planner") < 0.5);
    }
}

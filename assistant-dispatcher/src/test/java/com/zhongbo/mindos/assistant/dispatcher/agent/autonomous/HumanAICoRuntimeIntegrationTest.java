package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.ControlProtocol;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.ExplainabilityEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanAICoRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanPreferenceModel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanRuntimeSessionManager;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.InterventionEvent;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.InterventionManager;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.InterventionType;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.RuntimeHumanInterface;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.SharedDecision;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.SharedDecisionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.TrustModel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIRuntimeKernel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitiveCapability;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitivePluginContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitivePluginOutput;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitivePluginRegistry;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPolicy;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.MemoryCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.PlanningCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.PredictionCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ReasoningCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeObject;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeObjectType;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeOptimizer;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeScheduler;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeStateStore;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ToolUseCognitivePlugin;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PlanEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PlanScore;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PredictionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.StrategyEvolutionEngine;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanAICoRuntimeIntegrationTest {

    @Test
    void shouldLearnFromHumanCorrectionAndFinishGoal() {
        Goal goal = Goal.of("完成交付", 1.0);
        TaskGraph failingGraph = new TaskGraph(List.of(
                new TaskNode("task-1", "file.search", Map.of("query", "missing"), List.of(), "search", false, 1)
        ));
        TaskGraph successGraph = new TaskGraph(List.of(
                new TaskNode("task-1", "code.generate", Map.of("task", "delivery"), List.of(), "delivery", false, 1)
        ));
        AutonomousGraphExecutor executor = new AutonomousGraphExecutor(null, 2500) {
            @Override
            public GoalExecutionResult execute(Goal goal, TaskGraph graph, AutonomousPlanningContext context) {
                boolean success = graph.nodes().stream().anyMatch(node -> "code.generate".equals(node.target()));
                SkillResult result = success
                        ? SkillResult.success("code.generate", "delivery complete")
                        : SkillResult.failure("file.search", "missing file");
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
                        success ? List.of(graph.nodes().get(0).id()) : List.of()
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
        Evaluator evaluator = (result, evaluatedGoal) -> {
            boolean success = result != null && result.success();
            return new EvaluationResult(
                    evaluatedGoal.goalId(),
                    success ? GoalStatus.COMPLETED : GoalStatus.FAILED,
                    success,
                    false,
                    false,
                    success ? "done" : "failed-without-replan",
                    success ? result.successfulTaskIds() : List.of(),
                    success ? List.of() : List.of("task-1"),
                    result == null ? List.of() : result.failedTargets(),
                    success ? 1.0 : 0.2,
                    Instant.now()
            );
        };
        MultiAgentCoordinator coordinator = new MultiAgentCoordinator(List.of(), null, new PlanEvaluator(), new StrategyEvolutionEngine()) {
            @Override
            public PlanSelection selectBestPlan(Goal goal, AutonomousPlanningContext context, List<String> allowedAgentIds) {
                return new PlanSelection(
                        "balanced-planner",
                        "balanced",
                        failingGraph,
                        new PredictionResult(0.85, 0.18, 0.15, 0.10),
                        new PlanScore(0.80, 0.22),
                        List.of(),
                        "selected"
                );
            }
        };
        AGIMemory memory = new AGIMemory();
        HumanRuntimeSessionManager sessionManager = new HumanRuntimeSessionManager();
        RuntimeHumanInterface humanInterface = new RuntimeHumanInterface(sessionManager);
        HumanPreferenceModel preferenceModel = new HumanPreferenceModel(memory);
        TrustModel trustModel = new TrustModel(memory);
        SharedDecisionEngine sharedDecisionEngine = new SharedDecisionEngine(
                new ControlProtocol(),
                humanInterface,
                new ExplainabilityEngine(),
                preferenceModel,
                trustModel,
                memory
        );
        CognitivePlugin fixedPrediction = new CognitivePlugin() {
            @Override
            public String pluginId() {
                return "prediction.fixed";
            }

            @Override
            public CognitiveCapability capability() {
                return CognitiveCapability.PREDICTION;
            }

            @Override
            public RuntimeObject runtimeObject() {
                return new RuntimeObject("plugin.prediction.fixed", RuntimeObjectType.COGNITIVE_PLUGIN, "prediction", Map.of("fixed", true));
            }

            @Override
            public CognitivePluginOutput run(CognitivePluginContext context) {
                return new CognitivePluginOutput(
                        null,
                        Map.of(
                                "prediction.successProbability", 0.87,
                                "prediction.risk", 0.10,
                                "prediction.cost", 0.18
                        ),
                        0.87,
                        "fixed-prediction"
                );
            }

            @Override
            public int priority() {
                return 10;
            }
        };
        CognitivePluginRegistry registry = new CognitivePluginRegistry(List.of(
                new PlanningCognitivePlugin(coordinator),
                fixedPrediction,
                new MemoryCognitivePlugin(),
                new ReasoningCognitivePlugin(),
                new ToolUseCognitivePlugin(executor)
        ));
        RuntimeOptimizer optimizer = new RuntimeOptimizer();
        AGIRuntimeKernel kernel = new AGIRuntimeKernel(
                new RuntimeScheduler(registry, memory, optimizer),
                new ExecutionEngine(executor, evaluator),
                new RuntimeStateStore(),
                memory,
                optimizer,
                sharedDecisionEngine
        );
        HumanAICoRuntime coRuntime = new HumanAICoRuntime(
                sessionManager,
                humanInterface,
                preferenceModel,
                trustModel,
                new InterventionManager(kernel),
                sharedDecisionEngine
        );
        AutonomousLoopEngine engine = new AutonomousLoopEngine(
                kernel,
                new GoalMemory(),
                new RecordingExecutionMemoryFacade(),
                3,
                coRuntime
        );

        AutonomousGoalRunResult result = engine.run(goal, "u-human", Map.of(
                "human.feedback.queue", List.of(Map.of(
                        "rollback", true,
                        "approved", false,
                        "style", "concise",
                        "autonomyPreference", 0.74,
                        "riskTolerance", 0.36,
                        "notes", "switch to generation",
                        "corrections", Map.of("coruntime.overrideGraph", successGraph)
                ))
        ));

        assertTrue(result.success());
        assertEquals(GoalStatus.COMPLETED, result.goal().status());
        assertEquals(2, result.cycleCount());
        assertNotNull(result.latestSharedDecision());
        assertTrue(result.sharedDecisions().stream().anyMatch(SharedDecision::allowExecution));
        assertFalse(result.latestExplanation().summary().isBlank());
        assertEquals("concise", result.humanPreference().decisionStyle());
        assertTrue(result.sharedDecisions().size() >= 2);
        assertTrue(result.interventionEvents().stream().map(InterventionEvent::type).toList().containsAll(List.of(
                InterventionType.ROLLBACK,
                InterventionType.MODIFY
        )));
        assertTrue(result.trustScore() >= 0.0);
        assertNotNull(result.runtimeState());
    }

    private static final class RecordingExecutionMemoryFacade implements ExecutionMemoryFacade {
        private final List<SkillResult> recordedResults = new ArrayList<>();

        @Override
        public void record(OrchestrationExecutionResult result) {
        }

        @Override
        public void record(String userId, String userInput, SkillResult result, com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto trace) {
            if (result != null) {
                recordedResults.add(result);
            }
        }

        @Override
        public void commit(String userId, MemoryWriteBatch batch) {
        }
    }
}

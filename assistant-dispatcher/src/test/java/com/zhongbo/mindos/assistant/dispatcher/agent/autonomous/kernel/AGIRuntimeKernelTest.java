package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.DefaultEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.ControlProtocol;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.ExplainabilityEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanPreferenceModel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanRuntimeSession;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanRuntimeSessionManager;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.RuntimeHumanInterface;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.SharedDecisionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.TrustModel;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AGIRuntimeKernelTest {

    @Test
    void shouldSupportSuspendResumeAndMigrate() {
        AutonomousGraphExecutor executor = new AutonomousGraphExecutor(null, 2500) {
            @Override
            public GoalExecutionResult execute(Goal goal, TaskGraph graph, AutonomousPlanningContext context) {
                SkillResult result = SkillResult.success("code.generate", "done");
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
        CognitivePluginRegistry registry = new CognitivePluginRegistry(List.of(
                new ReasoningCognitivePlugin(),
                new MemoryCognitivePlugin(),
                new ToolUseCognitivePlugin(executor)
        ));
        RuntimeOptimizer optimizer = new RuntimeOptimizer();
        AGIMemory memory = new AGIMemory();
        RuntimeStateStore stateStore = new RuntimeStateStore();
        RuntimeScheduler scheduler = new RuntimeScheduler(registry, memory, optimizer);
        ExecutionEngine engine = new ExecutionEngine(executor, new DefaultEvaluator());
        AGIRuntimeKernel kernel = new AGIRuntimeKernel(scheduler, engine, stateStore, memory, optimizer);

        Task task = new Task(
                "task-kernel-1",
                Goal.of("generate code", 0.8),
                new TaskGraph(List.of(
                        new TaskNode("task-1", "code.generate", Map.of("task", "controller"), List.of(), "result", false, 1)
                )),
                ExecutionPolicy.LONG_RUNNING,
                Map.of("userId", "u-kernel")
        );
        TaskHandle handle = kernel.submit(task);
        kernel.migrate(handle, new Node("node:remote-a", "remote-a", Map.of("zone", "edge")));
        kernel.suspend(handle);

        ExecutionState executionState = kernel.resume(handle);

        assertNotNull(executionState.runtimeState());
        assertEquals(TaskState.COMPLETED, executionState.runtimeState().state());
        assertEquals("node:remote-a", executionState.runtimeState().context().assignedNode().nodeId());
        assertTrue(kernel.history(handle).cycleCount() >= 4);
        assertEquals("COMPLETED", memory.shortTerm().get("task:" + handle.taskId()).get("state"));
    }

    @Test
    void shouldWaitForHumanApprovalBeforeExecutingHighRiskTask() {
        AutonomousGraphExecutor executor = new AutonomousGraphExecutor(null, 2500) {
            @Override
            public GoalExecutionResult execute(Goal goal, TaskGraph graph, AutonomousPlanningContext context) {
                SkillResult result = SkillResult.success("deploy.prod", "deployed");
                return new GoalExecutionResult(
                        goal,
                        graph,
                        new TaskGraphExecutionResult(
                                result,
                                List.of(new TaskGraphExecutionResult.NodeResult("task-1", "deploy.prod", "success", result, false, 1)),
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
        CognitivePlugin fixedPrediction = new CognitivePlugin() {
            @Override
            public String pluginId() {
                return "prediction.high-risk";
            }

            @Override
            public CognitiveCapability capability() {
                return CognitiveCapability.PREDICTION;
            }

            @Override
            public RuntimeObject runtimeObject() {
                return new RuntimeObject("plugin.prediction.high-risk", RuntimeObjectType.COGNITIVE_PLUGIN, "prediction", Map.of("fixed", true));
            }

            @Override
            public CognitivePluginOutput run(CognitivePluginContext context) {
                return new CognitivePluginOutput(
                        null,
                        Map.of(
                                "prediction.successProbability", 0.36,
                                "prediction.risk", 0.84,
                                "prediction.cost", 0.90
                        ),
                        0.36,
                        "high-risk"
                );
            }

            @Override
            public int priority() {
                return 10;
            }
        };
        HumanRuntimeSessionManager sessionManager = new HumanRuntimeSessionManager();
        AGIMemory memory = new AGIMemory();
        RuntimeHumanInterface humanInterface = new RuntimeHumanInterface(sessionManager);
        SharedDecisionEngine sharedDecisionEngine = new SharedDecisionEngine(
                new ControlProtocol(),
                humanInterface,
                new ExplainabilityEngine(),
                new HumanPreferenceModel(memory),
                new TrustModel(memory),
                memory
        );
        CognitivePluginRegistry registry = new CognitivePluginRegistry(List.of(
                fixedPrediction,
                new ReasoningCognitivePlugin(),
                new MemoryCognitivePlugin(),
                new ToolUseCognitivePlugin(executor)
        ));
        RuntimeOptimizer optimizer = new RuntimeOptimizer();
        RuntimeStateStore stateStore = new RuntimeStateStore();
        RuntimeScheduler scheduler = new RuntimeScheduler(registry, memory, optimizer);
        ExecutionEngine engine = new ExecutionEngine(executor, new DefaultEvaluator());
        AGIRuntimeKernel kernel = new AGIRuntimeKernel(scheduler, engine, stateStore, memory, optimizer, sharedDecisionEngine);
        Task task = new Task(
                "task-kernel-risk",
                Goal.of("deploy production", 1.0),
                new TaskGraph(List.of(
                        new TaskNode("task-1", "deploy.prod", Map.of("target", "prod"), List.of(), "result", false, 1)
                )),
                ExecutionPolicy.AUTONOMOUS,
                Map.of("userId", "u-risk", "coruntime.shared", true)
        );
        try {
            sessionManager.activate(HumanRuntimeSession.from("u-risk", task.goal(), Map.of()));

            TaskHandle handle = kernel.submit(task);
            ExecutionState executionState = kernel.resume(handle);

            assertNotNull(executionState.runtimeState());
            assertEquals(TaskState.WAITING, executionState.runtimeState().state());
            assertEquals("waiting-human-approval", executionState.runtimeState().summary());
            assertFalse(sharedDecisionEngine.latest(handle).allowExecution());
            assertFalse(memory.shortTerm().get("coruntime:decision:" + handle.taskId()).isEmpty());
        } finally {
            sessionManager.clear();
        }
    }
}

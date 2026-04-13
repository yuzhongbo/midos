package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.DefaultEvaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
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
}

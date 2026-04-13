package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AGIRuntimeKernel {

    private final RuntimeScheduler scheduler;
    private final ExecutionEngine executionEngine;
    private final RuntimeStateStore stateStore;
    private final AGIMemory memory;
    private final RuntimeOptimizer optimizer;

    public AGIRuntimeKernel(RuntimeScheduler scheduler,
                            ExecutionEngine executionEngine,
                            RuntimeStateStore stateStore,
                            AGIMemory memory,
                            RuntimeOptimizer optimizer) {
        this.scheduler = scheduler;
        this.executionEngine = executionEngine;
        this.stateStore = stateStore;
        this.memory = memory;
        this.optimizer = optimizer;
    }

    public TaskHandle submit(Task task) {
        RuntimeState state = RuntimeState.initial(task);
        stateStore.save(state);
        checkpoint(state);
        return state.handle();
    }

    public ExecutionState resume(TaskHandle handle) {
        RuntimeState state = stateStore.state(handle).orElse(null);
        if (state == null) {
            return new ExecutionState(null, null, null, new ExecutionHistory(handle, java.util.List.of()));
        }
        if (state.state().isTerminal()) {
            return new ExecutionState(state, state.lastResult(), state.lastEvaluation(), stateStore.history(handle));
        }
        ExecutionPlan plan = scheduler.schedule(state.task(), state);
        RuntimeState scheduled = state.advance(
                plan,
                TaskState.RUNNING,
                state.pointer(),
                state.context().withAttributes(plan.attributes()).withAssignedNode(plan.executionNode()),
                state.lastResult(),
                state.lastEvaluation(),
                "scheduled"
        );
        stateStore.save(scheduled);
        checkpoint(scheduled);
        ExecutionState executionState = executionEngine.execute(plan, scheduled);
        RuntimeState updated = executionState.runtimeState();
        stateStore.save(updated);
        checkpoint(updated);
        ExecutionHistory history = stateStore.history(handle);
        optimizer.optimize(history);
        checkpointOptimizerHints(handle);
        GoalExecutionResult result = executionState.result();
        EvaluationResult evaluation = executionState.evaluation();
        return new ExecutionState(updated, result, evaluation, history);
    }

    public void suspend(TaskHandle handle) {
        RuntimeState state = stateStore.state(handle).orElse(null);
        if (state == null || state.state().isTerminal()) {
            return;
        }
        RuntimeState suspended = state.suspend();
        stateStore.save(suspended);
        checkpoint(suspended);
    }

    public void migrate(TaskHandle handle, Node target) {
        RuntimeState state = stateStore.state(handle).orElse(null);
        if (state == null || state.state().isTerminal()) {
            return;
        }
        RuntimeState migrated = state.migrate(target == null ? Node.local() : target).withState(TaskState.WAITING, "waiting-after-migration");
        stateStore.save(migrated);
        checkpoint(migrated);
    }

    public RuntimeState state(TaskHandle handle) {
        return stateStore.state(handle).orElse(null);
    }

    public ExecutionHistory history(TaskHandle handle) {
        return stateStore.history(handle);
    }

    private void checkpoint(RuntimeState state) {
        if (memory == null || state == null || state.handle() == null || state.handle().isEmpty()) {
            return;
        }
        String namespace = "task:" + state.handle().taskId();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", state.handle().taskId());
        snapshot.put("goalId", state.task().goal().goalId());
        snapshot.put("goal", state.task().goal().description());
        snapshot.put("state", state.state().name());
        snapshot.put("summary", state.summary());
        snapshot.put("policy", state.task().policy().name());
        snapshot.put("updatedAt", state.updatedAt().toString());
        snapshot.put("assignedNode", state.context().assignedNode().nodeId());
        snapshot.put("completedNodeIds", state.pointer().completedNodeIds());
        snapshot.put("failedTargets", state.pointer().failedTargets());
        memory.shortTerm().put(namespace, snapshot);
        memory.longTerm().link(state.handle().taskId(), state.task().goal().goalId());
        memory.semantic().put(namespace + ":summary", summaryText(state));
    }

    private void checkpointOptimizerHints(TaskHandle handle) {
        if (memory == null || optimizer == null || handle == null || handle.isEmpty()) {
            return;
        }
        Map<String, Object> hints = optimizer.hints(handle);
        if (hints.isEmpty()) {
            return;
        }
        String namespace = "optimizer:" + handle.taskId();
        memory.shortTerm().put(namespace, hints);
        memory.semantic().put(namespace, hints.toString());
        memory.longTerm().link(handle.taskId(), namespace);
    }

    private String summaryText(RuntimeState state) {
        if (state == null) {
            return "";
        }
        if (state.summary() != null && !state.summary().isBlank()) {
            return state.summary();
        }
        return state.task().goal().description() + "@" + Instant.now();
    }
}

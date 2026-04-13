package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;

import java.time.Instant;

public record RuntimeState(TaskHandle handle,
                           Task task,
                           ExecutionPlan plan,
                           TaskState state,
                           ExecutionPointer pointer,
                           RuntimeContext context,
                           GoalExecutionResult lastResult,
                           EvaluationResult lastEvaluation,
                           String summary,
                           Instant updatedAt) {

    public RuntimeState {
        handle = handle == null ? new TaskHandle(task == null ? "" : task.taskId()) : handle;
        task = task == null ? Task.fromGoal(null, ExecutionPolicy.AUTONOMOUS, java.util.Map.of()) : task;
        state = state == null ? TaskState.INIT : state;
        pointer = pointer == null ? ExecutionPointer.initial() : pointer;
        context = context == null ? RuntimeContext.empty() : context;
        summary = summary == null ? "" : summary.trim();
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static RuntimeState initial(Task task) {
        Task safeTask = task == null ? Task.fromGoal(null, ExecutionPolicy.AUTONOMOUS, java.util.Map.of()) : task;
        String userId = String.valueOf(safeTask.metadata().getOrDefault("userId", ""));
        return new RuntimeState(
                new TaskHandle(safeTask.taskId()),
                safeTask,
                null,
                TaskState.INIT,
                ExecutionPointer.initial(),
                new RuntimeContext(userId, safeTask.goal().description(), safeTask.metadata(), Node.local()),
                null,
                EvaluationResult.initial(safeTask.goal()),
                "submitted",
                Instant.now()
        );
    }

    public RuntimeState withPlan(ExecutionPlan nextPlan) {
        return new RuntimeState(handle, task, nextPlan, state, pointer, context, lastResult, lastEvaluation, summary, Instant.now());
    }

    public RuntimeState withState(TaskState nextState, String nextSummary) {
        return new RuntimeState(handle, task, plan, nextState, pointer, context, lastResult, lastEvaluation, nextSummary, Instant.now());
    }

    public RuntimeState withContext(RuntimeContext nextContext, String nextSummary) {
        return new RuntimeState(handle, task, plan, state, pointer, nextContext, lastResult, lastEvaluation, nextSummary, Instant.now());
    }

    public RuntimeState advance(ExecutionPlan nextPlan,
                                TaskState nextState,
                                ExecutionPointer nextPointer,
                                RuntimeContext nextContext,
                                GoalExecutionResult nextResult,
                                EvaluationResult nextEvaluation,
                                String nextSummary) {
        return new RuntimeState(handle, task, nextPlan, nextState, nextPointer, nextContext, nextResult, nextEvaluation, nextSummary, Instant.now());
    }

    public RuntimeState suspend() {
        return new RuntimeState(handle, task, plan, TaskState.SUSPENDED, pointer, context, lastResult, lastEvaluation, "suspended", Instant.now());
    }

    public RuntimeState migrate(Node target) {
        return new RuntimeState(handle, task, plan, state, pointer, context.withAssignedNode(target), lastResult, lastEvaluation, "migrated:" + (target == null ? "" : target.nodeId()), Instant.now());
    }
}

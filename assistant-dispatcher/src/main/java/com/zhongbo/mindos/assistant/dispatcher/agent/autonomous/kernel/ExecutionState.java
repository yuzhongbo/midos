package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;

public record ExecutionState(RuntimeState runtimeState,
                             GoalExecutionResult result,
                             EvaluationResult evaluation,
                             ExecutionHistory history) {

    public boolean completed() {
        return runtimeState != null && runtimeState.state() == TaskState.COMPLETED;
    }

    public boolean failed() {
        return runtimeState != null && runtimeState.state() == TaskState.FAILED;
    }

    public boolean suspended() {
        return runtimeState != null && runtimeState.state() == TaskState.SUSPENDED;
    }
}

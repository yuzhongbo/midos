package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

public interface DecisionExecutor {

    OrchestrationExecutionResult execute(TaskGraphPlan plan,
                                         DecisionOrchestrator.OrchestrationRequest request);
}

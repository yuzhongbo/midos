package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

public interface TaskGraphPlanner {

    TaskGraphPlan plan(Decision decision, DecisionOrchestrator.OrchestrationRequest request);
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

public interface OrchestrationPlanner {

    Decision plan(DecisionOrchestrator.UserInput input);

    default Decision replan(DecisionOrchestrator.UserInput input, Decision failedDecision) {
        return plan(input);
    }

    TaskGraph buildGraph(Decision decision);
}

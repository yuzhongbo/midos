package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

public interface OrchestrationPlanner {

    Decision plan(DecisionOrchestrator.UserInput input);

    TaskGraph buildGraph(Decision decision);
}

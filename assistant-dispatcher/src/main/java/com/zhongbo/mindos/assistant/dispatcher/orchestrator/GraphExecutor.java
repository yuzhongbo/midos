package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

public interface GraphExecutor {

    OrchestrationExecutionResult execute(TaskGraph graph);
}

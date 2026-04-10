package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchCandidate;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;

public record AgentDispatchResult(AgentMode mode,
                                  DecisionOrchestrator.OrchestrationOutcome outcome,
                                  TaskGraph plannedGraph,
                                  SearchCandidate selectedCandidate,
                                  String rationale) {
}

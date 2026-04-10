package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchCandidate;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

public interface System2Planner {

    PlanResult plan(AgentDispatchRequest request);

    record PlanResult(TaskGraph graph, SearchCandidate selectedCandidate, String rationale) {
    }
}

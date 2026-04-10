package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMatch;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchCandidate;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanningRequest;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultSystem2Planner implements System2Planner {

    private final SearchPlanner searchPlanner;
    private final ProcedureMemoryEngine procedureMemoryEngine;

    public DefaultSystem2Planner(SearchPlanner searchPlanner, ProcedureMemoryEngine procedureMemoryEngine) {
        this.searchPlanner = searchPlanner;
        this.procedureMemoryEngine = procedureMemoryEngine;
    }

    @Override
    public PlanResult plan(AgentDispatchRequest request) {
        if (request == null || request.decision() == null || request.orchestrationRequest() == null) {
            return new PlanResult(new TaskGraph(List.of()), null, "missing-request");
        }
        if (procedureMemoryEngine != null) {
            List<ProcedureMatch> matches = procedureMemoryEngine.matchTemplates(
                    request.orchestrationRequest().userId(),
                    request.orchestrationRequest().userInput(),
                    request.decision().target(),
                    1
            );
            if (!matches.isEmpty()) {
                return new PlanResult(toTaskGraph(matches.get(0)), null, "procedure-reuse");
            }
        }
        if (searchPlanner == null) {
            return new PlanResult(new TaskGraph(List.of()), null, "no-search-planner");
        }
        List<SearchCandidate> searchCandidates = searchPlanner.search(new SearchPlanningRequest(
                request.orchestrationRequest().userId(),
                request.orchestrationRequest().userInput(),
                request.decision().target(),
                request.decision().params(),
                3,
                3
        ));
        if (searchCandidates.isEmpty()) {
            return new PlanResult(new TaskGraph(List.of()), null, "search-empty");
        }
        SearchCandidate selected = searchCandidates.get(0);
        return new PlanResult(TaskGraph.linear(selected.path(), request.decision().params()), selected, "search-plan");
    }

    private TaskGraph toTaskGraph(ProcedureMatch match) {
        List<TaskNode> nodes = new ArrayList<>();
        int index = 1;
        for (var step : match.template().steps()) {
            nodes.add(new TaskNode(
                    "procedure-" + index,
                    step.target(),
                    step.params(),
                    step.dependsOn(),
                    step.saveAs(),
                    false
            ));
            index++;
        }
        return new TaskGraph(nodes);
    }
}

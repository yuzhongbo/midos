package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DefaultOrchestrationPlanner implements OrchestrationPlanner {

    private final DecisionPlanner decisionPlanner;
    private final TaskGraphPlanner taskGraphPlanner;
    private final GraphExecutionPlanStore planStore;

    @Autowired
    public DefaultOrchestrationPlanner(DecisionPlanner decisionPlanner,
                                       TaskGraphPlanner taskGraphPlanner,
                                       GraphExecutionPlanStore planStore) {
        this.decisionPlanner = decisionPlanner;
        this.taskGraphPlanner = taskGraphPlanner;
        this.planStore = planStore;
    }

    @Override
    public Decision plan(DecisionOrchestrator.UserInput input) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        Decision decision = decisionPlanner.plan(safeInput);
        return DecisionInputMetadata.enrich(decision, safeInput);
    }

    @Override
    public Decision replan(DecisionOrchestrator.UserInput input, Decision failedDecision) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        Decision decision = decisionPlanner.replan(safeInput, failedDecision);
        return DecisionInputMetadata.enrich(decision, safeInput);
    }

    @Override
    public TaskGraph buildGraph(Decision decision) {
        DecisionOrchestrator.OrchestrationRequest request = DecisionInputMetadata.requestOf(decision);
        Decision sanitizedDecision = DecisionInputMetadata.stripMetadata(decision);
        String validationMessage = DecisionInputMetadata.validationMessageOf(decision);
        TaskGraphPlan plan = sanitizedDecision != null && sanitizedDecision.requireClarify() && !validationMessage.isBlank()
                ? TaskGraphPlan.clarification(sanitizedDecision, sanitizedDecision.target(), validationMessage)
                : taskGraphPlanner.plan(sanitizedDecision, request);
        TaskGraphPlan safePlan = plan == null
                ? TaskGraphPlan.clarification(sanitizedDecision, sanitizedDecision == null ? "" : sanitizedDecision.target(), "missing task graph plan")
                : plan;
        planStore.store(safePlan, request);
        return safePlan.taskGraph();
    }

    void setSearchPlanner(SearchPlanner searchPlanner) {
        DefaultTaskGraphPlanner planner = defaultTaskGraphPlanner();
        if (planner != null) {
            planner.setSearchPlanner(searchPlanner);
        }
    }

    void setProceduralMemory(ProceduralMemory proceduralMemory) {
        DefaultTaskGraphPlanner planner = defaultTaskGraphPlanner();
        if (planner != null) {
            planner.setProceduralMemory(proceduralMemory);
        }
    }

    private DefaultTaskGraphPlanner defaultTaskGraphPlanner() {
        return taskGraphPlanner instanceof DefaultTaskGraphPlanner planner ? planner : null;
    }
}

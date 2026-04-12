package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Decision explicitDecision = explicitSkillDecision(safeInput);
        if (explicitDecision != null) {
            return explicitDecision;
        }
        DecisionOrchestrator.OrchestrationRequest request = safeInput.toRequest();
        Decision decision = decisionPlanner.plan(
                request.userInput(),
                "",
                request.skillContext() == null ? java.util.Map.of() : request.skillContext().attributes(),
                request.skillContext()
        );
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

    private Decision explicitSkillDecision(DecisionOrchestrator.UserInput input) {
        if (input == null || input.userInput() == null) {
            return null;
        }
        String trimmed = input.userInput().trim();
        if (!trimmed.startsWith("skill:")) {
            return null;
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        String skillName = tokens[0].substring("skill:".length()).trim();
        if (skillName.isBlank()) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            int splitIndex = token.indexOf('=');
            if (splitIndex > 0 && splitIndex < token.length() - 1) {
                params.put(token.substring(0, splitIndex), token.substring(splitIndex + 1));
            }
        }
        params.put("_target", skillName);
        return DecisionInputMetadata.enrich(
                new Decision(skillName, skillName, params, 1.0, false),
                input
        );
    }
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.DecisionSignal;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
        Decision decision = decisionPlanner.plan(safeInput, collectSignals(safeInput));
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

    private List<DecisionSignal> collectSignals(DecisionOrchestrator.UserInput input) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        List<DecisionSignal> signals = new ArrayList<>();
        addSignal(signals, explicitSkillTarget(safeInput.userInput()), 1.0, "explicit");
        Map<String, Object> attributes = safeInput.skillContext() == null || safeInput.skillContext().attributes() == null
                ? Map.of()
                : safeInput.skillContext().attributes();
        addSignal(signals, stringValue(attributes.get("explicitTarget")), 0.99, "explicit");
        addSignal(signals, stringValue(attributes.get("explicitSkill")), 0.99, "explicit");
        addSignal(signals, stringValue(attributes.get("_target")), 0.98, "explicit");
        addSignal(signals, stringValue(attributes.get("target")), 0.97, "explicit");
        double semanticConfidence = semanticConfidence(attributes);
        addSignal(signals, stringValue(attributes.get(SemanticAnalysisResult.ATTR_SUGGESTED_SKILL)), semanticConfidence, "semantic");
        addSignal(signals, stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT)), Math.max(0.60, semanticConfidence - 0.05), "semantic");
        return List.copyOf(signals);
    }

    private void addSignal(List<DecisionSignal> signals, String target, double score, String source) {
        if (target == null || target.isBlank()) {
            return;
        }
        signals.add(new DecisionSignal(target, score, source));
    }

    private String explicitSkillTarget(String userInput) {
        if (userInput == null) {
            return "";
        }
        String trimmed = userInput.trim();
        if (!trimmed.startsWith("skill:")) {
            return "";
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return "";
        }
        return tokens[0].substring("skill:".length()).trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double semanticConfidence(Map<String, Object> attributes) {
        Object raw = attributes.get(SemanticAnalysisResult.ATTR_CONFIDENCE);
        if (raw instanceof Number number) {
            return Math.max(0.0, Math.min(1.0, number.doubleValue()));
        }
        return 0.82;
    }
}

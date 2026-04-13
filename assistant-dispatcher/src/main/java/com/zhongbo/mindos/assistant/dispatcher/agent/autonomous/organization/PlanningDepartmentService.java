package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlanningDepartmentService {

    private final MultiAgentCoordinator coordinator;

    public PlanningDepartmentService(MultiAgentCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public PlanningOutcome plan(StrategyDirective directive,
                                AutonomousPlanningContext context,
                                AIOrganization organization) {
        StrategyDirective safeDirective = directive == null
                ? new StrategyDirective(com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal.of("", 0.0), "", "balanced", Map.of(), List.of(), Map.of())
                : directive;
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        AIOrganization safeOrganization = organization == null
                ? AIOrganization.bootstrap("MindOS Organization", List.of())
                : organization;
        LinkedHashMap<String, Object> mergedProfile = new LinkedHashMap<>(safeContext.profileContext());
        mergedProfile.putAll(safeDirective.profileContext());
        mergedProfile.put("orgPlanningScenarioDepth", safeOrganization.planningDept() == null ? 1 : safeOrganization.planningDept().intMetadata("scenarioDepth", 1));
        mergedProfile.put("orgExecutionRetryCap", safeOrganization.executionDept() == null ? 3 : safeOrganization.executionDept().intMetadata("retryBudgetCap", 3));
        mergedProfile.put("orgEvaluationAnalystDepth", safeOrganization.evaluationDept() == null ? 1 : safeOrganization.evaluationDept().intMetadata("analystDepth", 1));
        AutonomousPlanningContext enrichedContext = new AutonomousPlanningContext(
                safeContext.userId(),
                safeContext.userInput(),
                mergedProfile,
                safeContext.goalMemory(),
                safeContext.iteration(),
                safeContext.lastResult(),
                safeContext.lastEvaluation(),
                safeContext.excludedTargets()
        );
        List<String> allowedAgentIds = safeOrganization.activePlannerIds();
        MultiAgentCoordinator.PlanSelection selection = coordinator == null
                ? MultiAgentCoordinator.PlanSelection.empty()
                : coordinator.selectBestPlan(safeDirective.goal(), enrichedContext, allowedAgentIds);
        return new PlanningOutcome(safeDirective, enrichedContext, selection);
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PlannerAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AIOrganizationRuntime {

    private final StrategyDepartmentService strategyDepartmentService;
    private final PlanningDepartmentService planningDepartmentService;
    private final ExecutionDepartmentService executionDepartmentService;
    private final EvaluationDepartmentService evaluationDepartmentService;
    private final OrgDecisionEngine orgDecisionEngine;
    private final OrgRestructuringEngine orgRestructuringEngine;
    private final OrgMemory orgMemory;
    private volatile AIOrganization organization;

    @Autowired
    public AIOrganizationRuntime(StrategyDepartmentService strategyDepartmentService,
                                 PlanningDepartmentService planningDepartmentService,
                                 ExecutionDepartmentService executionDepartmentService,
                                 EvaluationDepartmentService evaluationDepartmentService,
                                 OrgDecisionEngine orgDecisionEngine,
                                 OrgRestructuringEngine orgRestructuringEngine,
                                 OrgMemory orgMemory,
                                 List<PlannerAgent> plannerAgents) {
        this.strategyDepartmentService = strategyDepartmentService;
        this.planningDepartmentService = planningDepartmentService;
        this.executionDepartmentService = executionDepartmentService;
        this.evaluationDepartmentService = evaluationDepartmentService;
        this.orgDecisionEngine = orgDecisionEngine;
        this.orgRestructuringEngine = orgRestructuringEngine;
        this.orgMemory = orgMemory;
        this.organization = AIOrganization.bootstrap(
                "MindOS Organization",
                plannerAgents == null ? List.of() : plannerAgents.stream().map(PlannerAgent::agentId).toList()
        );
    }

    public OrganizationCycleResult runCycle(Goal goal, AutonomousPlanningContext context) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        AIOrganization organizationBefore = organization == null
                ? AIOrganization.bootstrap("MindOS Organization", List.of())
                : organization;
        StrategyDirective directive = strategyDepartmentService == null
                ? new StrategyDirective(safeGoal, safeGoal.description(), "balanced", java.util.Map.of(), java.util.List.of(), java.util.Map.of())
                : strategyDepartmentService.define(safeGoal, organizationBefore, orgMemory, context);
        PlanningOutcome planningOutcome = planningDepartmentService == null
                ? new PlanningOutcome(directive, AutonomousPlanningContext.safe(context), MultiAgentCoordinator.PlanSelection.empty())
                : planningDepartmentService.plan(directive, context, organizationBefore);
        GoalExecutionResult executionResult = executionDepartmentService == null
                ? null
                : executionDepartmentService.execute(safeGoal, planningOutcome);
        OrganizationAssessment assessment = evaluationDepartmentService == null
                ? OrganizationAssessment.empty(safeGoal, "evaluation department unavailable")
                : evaluationDepartmentService.assess(safeGoal, organizationBefore, planningOutcome, executionResult, orgMemory);
        OrganizationDecision decision = orgDecisionEngine == null
                ? OrganizationDecision.maintain("org decision engine unavailable")
                : orgDecisionEngine.decide(safeGoal, assessment.kpi());
        AIOrganization organizationAfter = orgRestructuringEngine == null
                ? organizationBefore
                : orgRestructuringEngine.restructure(organizationBefore, decision, assessment.kpi(), orgMemory);
        OrgMemory.OrgExecutionTrace orgTrace = orgMemory == null
                ? null
                : orgMemory.record(
                organizationBefore,
                organizationAfter,
                directive,
                planningOutcome == null ? MultiAgentCoordinator.PlanSelection.empty() : planningOutcome.selection(),
                executionResult,
                assessment == null ? null : assessment.evaluation(),
                assessment == null ? KPI.empty() : assessment.kpi(),
                decision
        );
        organization = organizationAfter;
        return new OrganizationCycleResult(
                organizationBefore,
                organizationAfter,
                directive,
                planningOutcome,
                executionResult,
                assessment,
                decision,
                orgTrace
        );
    }

    public AIOrganization currentOrganization() {
        return organization;
    }

    public OrgMemory orgMemory() {
        return orgMemory;
    }
}

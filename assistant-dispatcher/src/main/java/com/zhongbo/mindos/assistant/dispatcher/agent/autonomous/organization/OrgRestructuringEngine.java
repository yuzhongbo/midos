package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrgRestructuringEngine {

    public AIOrganization restructure(AIOrganization organization,
                                      OrganizationDecision decision,
                                      KPI kpi,
                                      OrgMemory memory) {
        AIOrganization safeOrganization = organization == null
                ? AIOrganization.bootstrap("MindOS Organization", java.util.List.of())
                : organization;
        OrganizationDecision safeDecision = decision == null ? OrganizationDecision.maintain("no-decision") : decision;
        KPI safeKpi = kpi == null ? KPI.empty() : kpi;
        if (!safeDecision.requiresRestructure()) {
            return safeOrganization.withMetadata(Map.of(
                    "lastDecisionType", safeDecision.type().name(),
                    "lastDecisionSummary", safeDecision.summary(),
                    "lastKpiHealth", round(safeKpi.healthScore())
            ));
        }
        Department strategy = safeOrganization.strategyDept();
        Department planning = safeOrganization.planningDept();
        Department execution = safeOrganization.executionDept();
        Department evaluation = safeOrganization.evaluationDept();
        switch (safeDecision.type()) {
            case EXPAND_PLANNING -> {
                planning = planning.withMetadata(Map.of(
                        "scenarioDepth", planning.intMetadata("scenarioDepth", 1) + Math.max(1, safeDecision.staffingDelta()),
                        "planningBudget", planning.intMetadata("planningBudget", planning.headcount()) + Math.max(1, safeDecision.staffingDelta())
                ));
                strategy = strategy.withMetadata("strategyMode", safeDecision.strategyMode());
            }
            case REDUCE_EXECUTION -> {
                int nextRetryBudget = Math.max(1, execution.intMetadata("retryBudgetCap", 3) - 1);
                execution = execution
                        .setAgentActive("retry-controller", false)
                        .withMetadata(Map.of(
                                "retryBudgetCap", nextRetryBudget,
                                "executionMode", "lean"
                        ));
            }
            case ENHANCE_EVALUATION -> {
                evaluation = evaluation
                        .appendAgent(new OrganizationAgent(
                                "evaluation-auditor-r" + (safeOrganization.revision() + 1),
                                AgentRole.ANALYST,
                                true,
                                Map.of("advisoryOnly", true)
                        ))
                        .withMetadata(Map.of(
                                "analystDepth", evaluation.intMetadata("analystDepth", 1) + 1,
                                "evaluationMode", "audited"
                        ));
            }
            case ADJUST_STRATEGY -> strategy = strategy.withMetadata(Map.of(
                    "strategyMode", safeDecision.strategyMode(),
                    "resourceFocus", "growth"
            ));
            case RESTRUCTURE_ORGANIZATION -> {
                strategy = strategy.withMetadata("strategyMode", safeDecision.strategyMode());
                planning = planning.withMetadata("scenarioDepth", planning.intMetadata("scenarioDepth", 1) + 1);
                execution = execution.withMetadata("retryBudgetCap", Math.max(1, execution.intMetadata("retryBudgetCap", 3) - 1));
                evaluation = evaluation.withMetadata("analystDepth", evaluation.intMetadata("analystDepth", 1) + 1);
            }
            case MAINTAIN -> {
            }
        }
        return safeOrganization.revise(
                strategy,
                planning,
                execution,
                evaluation,
                Map.of(
                        "lastDecisionType", safeDecision.type().name(),
                        "lastDecisionSummary", safeDecision.summary(),
                        "lastKpiHealth", round(safeKpi.healthScore()),
                        "planningDeptPerformance", round(memory == null ? 0.5 : memory.departmentPerformance(DepartmentType.PLANNING)),
                        "evaluationDeptPerformance", round(memory == null ? 0.5 : memory.departmentPerformance(DepartmentType.EVALUATION))
                )
        );
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

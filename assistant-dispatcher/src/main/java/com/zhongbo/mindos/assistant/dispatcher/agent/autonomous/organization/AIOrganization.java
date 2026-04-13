package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record AIOrganization(String orgName,
                             Department strategyDept,
                             Department planningDept,
                             Department executionDept,
                             Department evaluationDept,
                             int revision,
                             Map<String, Object> metadata) {

    public AIOrganization {
        orgName = orgName == null || orgName.isBlank() ? "AI Organization" : orgName.trim();
        revision = Math.max(1, revision);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static AIOrganization bootstrap(String orgName, List<String> plannerAgentIds) {
        List<String> planners = plannerAgentIds == null || plannerAgentIds.isEmpty()
                ? List.of("balanced-planner", "conservative-planner", "aggressive-planner")
                : plannerAgentIds.stream()
                .filter(agentId -> agentId != null && !agentId.isBlank())
                .distinct()
                .collect(Collectors.toList());
        Department strategy = new Department(
                "strategy",
                "Strategy Department",
                DepartmentType.STRATEGY,
                List.of(
                        new OrganizationAgent("org-strategist", AgentRole.STRATEGIST, true, Map.of("title", "Chief Strategy Officer")),
                        new OrganizationAgent("resource-allocator", AgentRole.OPTIMIZER, true, Map.of("title", "Resource Allocator"))
                ),
                Map.of(
                        "strategyMode", "balanced",
                        "resourceFocus", "goal-delivery"
                )
        );
        Department planning = new Department(
                "planning",
                "Planning Department",
                DepartmentType.PLANNING,
                planners.stream()
                        .map(agentId -> new OrganizationAgent(agentId, AgentRole.PLANNER, true, Map.of("beanBacked", true)))
                        .toList(),
                Map.of(
                        "scenarioDepth", 1,
                        "proposalQuota", planners.size(),
                        "planningBudget", planners.size()
                )
        );
        Department execution = new Department(
                "execution",
                "Execution Department",
                DepartmentType.EXECUTION,
                List.of(
                        new OrganizationAgent("task-executor", AgentRole.EXECUTOR, true, Map.of("title", "Primary Executor")),
                        new OrganizationAgent("retry-controller", AgentRole.EXECUTOR, true, Map.of("title", "Retry Controller"))
                ),
                Map.of(
                        "retryBudgetCap", 3,
                        "latencyBudgetMs", 2500,
                        "executionMode", "controlled"
                )
        );
        Department evaluation = new Department(
                "evaluation",
                "Evaluation Department",
                DepartmentType.EVALUATION,
                List.of(
                        new OrganizationAgent("kpi-analyst", AgentRole.ANALYST, true, Map.of("title", "KPI Analyst")),
                        new OrganizationAgent("org-optimizer", AgentRole.OPTIMIZER, true, Map.of("title", "Organization Optimizer"))
                ),
                Map.of(
                        "analystDepth", 1,
                        "evaluationMode", "kpi-first"
                )
        );
        return new AIOrganization(
                orgName,
                strategy,
                planning,
                execution,
                evaluation,
                1,
                Map.of(
                        "governanceModel", "departmental-runtime",
                        "structureType", "digital-company"
                )
        );
    }

    public List<Department> departments() {
        return List.of(strategyDept, planningDept, executionDept, evaluationDept);
    }

    public List<String> activePlannerIds() {
        return planningDept == null ? List.of() : planningDept.activeAgentIds();
    }

    public AIOrganization withMetadata(Map<String, Object> additions) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        if (additions != null) {
            merged.putAll(additions);
        }
        return new AIOrganization(orgName, strategyDept, planningDept, executionDept, evaluationDept, revision, merged);
    }

    public AIOrganization revise(Department nextStrategyDept,
                                 Department nextPlanningDept,
                                 Department nextExecutionDept,
                                 Department nextEvaluationDept,
                                 Map<String, Object> additions) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        if (additions != null) {
            merged.putAll(additions);
        }
        return new AIOrganization(
                orgName,
                nextStrategyDept == null ? strategyDept : nextStrategyDept,
                nextPlanningDept == null ? planningDept : nextPlanningDept,
                nextExecutionDept == null ? executionDept : nextExecutionDept,
                nextEvaluationDept == null ? evaluationDept : nextEvaluationDept,
                revision + 1,
                merged
        );
    }
}

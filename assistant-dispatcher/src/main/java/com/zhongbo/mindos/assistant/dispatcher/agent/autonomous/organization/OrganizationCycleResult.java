package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;

public record OrganizationCycleResult(AIOrganization organizationBefore,
                                      AIOrganization organizationAfter,
                                      StrategyDirective strategyDirective,
                                      PlanningOutcome planningOutcome,
                                      GoalExecutionResult executionResult,
                                      OrganizationAssessment assessment,
                                      OrganizationDecision decision,
                                      OrgMemory.OrgExecutionTrace orgTrace) {

    public boolean hasPlan() {
        return planningOutcome != null && planningOutcome.hasPlan();
    }

    public WorldMemory.ExecutionTrace worldTrace() {
        return assessment == null ? null : assessment.worldTrace();
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalStatus;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;

import java.time.Instant;
import java.util.List;

public record OrganizationAssessment(EvaluationResult evaluation,
                                     KPI kpi,
                                     WorldMemory.ExecutionTrace worldTrace) {

    public static OrganizationAssessment empty(Goal goal, String summary) {
        return new OrganizationAssessment(
                new EvaluationResult(
                        goal == null ? "" : goal.goalId(),
                        GoalStatus.FAILED,
                        false,
                        false,
                        false,
                        summary == null ? "organization assessment unavailable" : summary,
                        List.of(),
                        List.of(),
                        List.of(),
                        0.0,
                        Instant.now()
                ),
                KPI.empty(),
                null
        );
    }
}

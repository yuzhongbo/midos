package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ExecutionDepartmentService {

    private final AutonomousGraphExecutor executor;

    public ExecutionDepartmentService(AutonomousGraphExecutor executor) {
        this.executor = executor;
    }

    public GoalExecutionResult execute(Goal goal, PlanningOutcome planningOutcome) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        PlanningOutcome safePlanningOutcome = planningOutcome == null
                ? new PlanningOutcome(null, com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext.empty(), com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator.PlanSelection.empty())
                : planningOutcome;
        if (!safePlanningOutcome.hasPlan() || executor == null) {
            return new GoalExecutionResult(
                    safeGoal,
                    safePlanningOutcome == null ? new TaskGraph(List.of(), List.of()) : safePlanningOutcome.graph(),
                    null,
                    SkillResult.failure("organization.execution", "execution department unavailable"),
                    safePlanningOutcome == null || safePlanningOutcome.planningContext() == null ? "" : safePlanningOutcome.planningContext().userId(),
                    safeGoal.description(),
                    safePlanningOutcome == null || safePlanningOutcome.planningContext() == null ? 1 : safePlanningOutcome.planningContext().iteration(),
                    Instant.now(),
                    Instant.now()
            );
        }
        return executor.execute(safeGoal, safePlanningOutcome.graph(), safePlanningOutcome.planningContext());
    }
}

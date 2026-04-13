package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.CivilizationMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.Explanation;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanPreference;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.InterventionEvent;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.SharedDecision;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization.DigitalCivilization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionHistory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.KPI;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationDecision;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;

import java.time.Instant;
import java.util.List;

public record AutonomousGoalRunResult(Goal goal,
                                      List<GoalMemory.GoalTrace> traces,
                                      List<WorldMemory.ExecutionTrace> worldTraces,
                                       AIOrganization organization,
                                       List<OrgMemory.OrgExecutionTrace> orgTraces,
                                       DigitalCivilization civilization,
                                       List<CivilizationMemory.CivilizationTrace> civilizationTraces,
                                       RuntimeState runtimeState,
                                       ExecutionHistory runtimeHistory,
                                       List<SharedDecision> sharedDecisions,
                                       List<InterventionEvent> interventionEvents,
                                       HumanPreference humanPreference,
                                       double trustScore,
                                       String stopReason,
                                       Instant startedAt,
                                       Instant finishedAt) {

    public AutonomousGoalRunResult(Goal goal,
                                   List<GoalMemory.GoalTrace> traces,
                                   String stopReason,
                                   Instant startedAt,
                                   Instant finishedAt) {
        this(goal, traces, List.of(), null, List.of(), null, List.of(), null, null, List.of(), List.of(), null, 0.0, stopReason, startedAt, finishedAt);
    }

    public AutonomousGoalRunResult(Goal goal,
                                   List<GoalMemory.GoalTrace> traces,
                                   List<WorldMemory.ExecutionTrace> worldTraces,
                                   String stopReason,
                                   Instant startedAt,
                                   Instant finishedAt) {
        this(goal, traces, worldTraces, null, List.of(), null, List.of(), null, null, List.of(), List.of(), null, 0.0, stopReason, startedAt, finishedAt);
    }

    public AutonomousGoalRunResult {
        traces = traces == null ? List.of() : List.copyOf(traces);
        worldTraces = worldTraces == null ? List.of() : List.copyOf(worldTraces);
        orgTraces = orgTraces == null ? List.of() : List.copyOf(orgTraces);
        civilizationTraces = civilizationTraces == null ? List.of() : List.copyOf(civilizationTraces);
        sharedDecisions = sharedDecisions == null ? List.of() : List.copyOf(sharedDecisions);
        interventionEvents = interventionEvents == null ? List.of() : List.copyOf(interventionEvents);
        humanPreference = humanPreference == null ? HumanPreference.defaultPreference() : humanPreference;
        trustScore = clamp(trustScore);
        stopReason = stopReason == null ? "" : stopReason.trim();
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
    }

    public boolean success() {
        return goal != null && goal.isCompleted();
    }

    public int cycleCount() {
        return traces.size();
    }

    public KPI latestKpi() {
        return orgTraces.isEmpty() ? KPI.empty() : orgTraces.get(orgTraces.size() - 1).kpi();
    }

    public OrganizationDecision latestDecision() {
        return orgTraces.isEmpty()
                ? OrganizationDecision.maintain("no-org-decision")
                : orgTraces.get(orgTraces.size() - 1).decision();
    }

    public SharedDecision latestSharedDecision() {
        return sharedDecisions.isEmpty() ? null : sharedDecisions.get(sharedDecisions.size() - 1);
    }

    public Explanation latestExplanation() {
        SharedDecision latest = latestSharedDecision();
        return latest == null ? Explanation.empty() : latest.explanation();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

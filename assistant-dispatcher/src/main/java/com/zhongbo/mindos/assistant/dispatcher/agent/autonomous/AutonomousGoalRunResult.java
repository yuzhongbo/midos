package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.time.Instant;
import java.util.List;

public record AutonomousGoalRunResult(Goal goal,
                                      List<GoalMemory.GoalTrace> traces,
                                      String stopReason,
                                      Instant startedAt,
                                      Instant finishedAt) {

    public AutonomousGoalRunResult {
        traces = traces == null ? List.of() : List.copyOf(traces);
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
}

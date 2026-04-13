package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;

import java.time.Instant;
import java.util.List;

public record AutonomousGoalRunResult(Goal goal,
                                      List<GoalMemory.GoalTrace> traces,
                                      List<WorldMemory.ExecutionTrace> worldTraces,
                                      String stopReason,
                                      Instant startedAt,
                                      Instant finishedAt) {

    public AutonomousGoalRunResult(Goal goal,
                                   List<GoalMemory.GoalTrace> traces,
                                   String stopReason,
                                   Instant startedAt,
                                   Instant finishedAt) {
        this(goal, traces, List.of(), stopReason, startedAt, finishedAt);
    }

    public AutonomousGoalRunResult {
        traces = traces == null ? List.of() : List.copyOf(traces);
        worldTraces = worldTraces == null ? List.of() : List.copyOf(worldTraces);
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

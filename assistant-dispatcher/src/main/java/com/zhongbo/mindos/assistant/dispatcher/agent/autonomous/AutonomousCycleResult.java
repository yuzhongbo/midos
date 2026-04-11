package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

public record AutonomousCycleResult(List<AutonomousGoal> candidates,
                                    AutonomousGoal goal,
                                    MasterOrchestrationResult execution,
                                    AutonomousEvaluation evaluation,
                                    MemoryEvolutionResult memoryEvolution,
                                    long durationMs,
                                    int tokenEstimate,
                                    Instant startedAt,
                                    Instant finishedAt) {

    public AutonomousCycleResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        durationMs = Math.max(0L, durationMs);
        tokenEstimate = Math.max(0, tokenEstimate);
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
    }

    public boolean success() {
        return evaluation != null && evaluation.success();
    }

    public long elapsedMs() {
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}

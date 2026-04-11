package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.time.Instant;
import java.util.List;

public record AutonomousLoopResult(String userId,
                                   List<AutonomousCycleResult> cycles,
                                   boolean stopped,
                                   String stopReason,
                                   Instant startedAt,
                                   Instant finishedAt) {

    public AutonomousLoopResult {
        userId = userId == null ? "" : userId.trim();
        cycles = cycles == null ? List.of() : List.copyOf(cycles);
        stopReason = stopReason == null ? "" : stopReason.trim();
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
    }

    public int cycleCount() {
        return cycles.size();
    }

    public long successCount() {
        return cycles.stream().filter(AutonomousCycleResult::success).count();
    }

    public long failureCount() {
        return cycles.stream().filter(cycle -> !cycle.success()).count();
    }
}

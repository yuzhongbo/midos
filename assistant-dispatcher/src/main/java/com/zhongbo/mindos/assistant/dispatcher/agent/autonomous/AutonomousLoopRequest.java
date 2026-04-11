package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.util.LinkedHashMap;
import java.util.Map;

public record AutonomousLoopRequest(String userId,
                                    Map<String, Object> profileContext,
                                    int goalLimit,
                                    int maxCycles,
                                    long pauseMillis,
                                    String workerId) {

    public AutonomousLoopRequest {
        userId = userId == null ? "" : userId.trim();
        profileContext = profileContext == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(profileContext));
        goalLimit = Math.max(1, goalLimit);
        maxCycles = maxCycles;
        pauseMillis = Math.max(0L, pauseMillis);
        workerId = workerId == null || workerId.isBlank() ? "autonomous-loop" : workerId.trim();
    }

    public static AutonomousLoopRequest of(String userId) {
        return new AutonomousLoopRequest(userId, Map.of(), 1, 1, 0L, "autonomous-loop");
    }

    public AutonomousLoopRequest withGoalLimit(int newGoalLimit) {
        return new AutonomousLoopRequest(userId, profileContext, newGoalLimit, maxCycles, pauseMillis, workerId);
    }

    public AutonomousLoopRequest withMaxCycles(int newMaxCycles) {
        return new AutonomousLoopRequest(userId, profileContext, goalLimit, newMaxCycles, pauseMillis, workerId);
    }

    public AutonomousLoopRequest withPauseMillis(long newPauseMillis) {
        return new AutonomousLoopRequest(userId, profileContext, goalLimit, maxCycles, newPauseMillis, workerId);
    }
}

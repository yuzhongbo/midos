package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.time.Instant;
import java.util.List;

public record EvaluationResult(String goalId,
                               GoalStatus goalStatus,
                               boolean success,
                               boolean partial,
                               boolean requiresReplan,
                               String summary,
                               List<String> completedTaskIds,
                               List<String> remainingTaskIds,
                               List<String> failedTargets,
                               double progressScore,
                               Instant evaluatedAt) {

    public EvaluationResult {
        goalId = goalId == null ? "" : goalId.trim();
        goalStatus = goalStatus == null ? GoalStatus.ACTIVE : goalStatus;
        summary = summary == null ? "" : summary.trim();
        completedTaskIds = completedTaskIds == null ? List.of() : List.copyOf(completedTaskIds);
        remainingTaskIds = remainingTaskIds == null ? List.of() : List.copyOf(remainingTaskIds);
        failedTargets = failedTargets == null ? List.of() : List.copyOf(failedTargets);
        progressScore = clamp(progressScore);
        evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
    }

    public static EvaluationResult initial(Goal goal) {
        return new EvaluationResult(
                goal == null ? "" : goal.goalId(),
                goal == null ? GoalStatus.ACTIVE : goal.status(),
                false,
                false,
                true,
                "",
                List.of(),
                List.of(),
                List.of(),
                0.0,
                Instant.now()
        );
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isPartial() {
        return partial;
    }

    public boolean needsReplan() {
        return requiresReplan;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.time.Instant;
import java.util.List;

public record AutonomousEvaluation(String goalId,
                                   AutonomousGoalType goalType,
                                   boolean success,
                                   double score,
                                   String feedback,
                                   String nextAction,
                                   String resultSkill,
                                   String resultOutput,
                                   List<String> reasons,
                                   Instant evaluatedAt) {

    public AutonomousEvaluation {
        goalId = goalId == null ? "" : goalId.trim();
        goalType = goalType == null ? AutonomousGoalType.FALLBACK : goalType;
        score = clamp(score);
        feedback = feedback == null ? "" : feedback.trim();
        nextAction = nextAction == null ? "" : nextAction.trim();
        resultSkill = resultSkill == null ? "" : resultSkill.trim();
        resultOutput = resultOutput == null ? "" : resultOutput.trim();
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
    }

    public double reward() {
        return success ? 1.0 : -1.0;
    }

    public String summary() {
        return "goal=" + goalId
                + ",success=" + success
                + ",score=" + round(score)
                + ",feedback=" + feedback
                + ",nextAction=" + nextAction;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

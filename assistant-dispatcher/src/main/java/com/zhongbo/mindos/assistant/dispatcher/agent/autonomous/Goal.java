package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record Goal(String goalId,
                   String description,
                   GoalStatus status,
                   double priority,
                   Map<String, Object> metadata) {

    public Goal {
        goalId = normalizeGoalId(goalId, description);
        description = description == null ? "" : description.trim();
        status = status == null ? GoalStatus.ACTIVE : status;
        priority = clamp(priority);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static Goal of(String goal, double priority) {
        return new Goal("", goal, GoalStatus.ACTIVE, priority, Map.of());
    }

    public static Goal from(AutonomousGoal goal) {
        if (goal == null) {
            return new Goal("", "", GoalStatus.ACTIVE, 0.0, Map.of());
        }
        Map<String, Object> metadata = new LinkedHashMap<>(goal.params());
        metadata.put("autonomousGoalType", goal.type().name());
        metadata.put("autonomousGoalTarget", goal.target());
        metadata.put("autonomousGoalSourceId", goal.sourceId());
        metadata.put("autonomousGoalTitle", goal.title());
        metadata.put("autonomousGoalReasons", goal.reasons());
        return new Goal(
                goal.goalId(),
                goal.title().isBlank() ? goal.objective() : goal.title(),
                GoalStatus.ACTIVE,
                goal.priority() / 100.0,
                metadata
        );
    }

    public String goal() {
        return description;
    }

    public boolean isCompleted() {
        return status == GoalStatus.COMPLETED;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public Goal withStatus(GoalStatus nextStatus) {
        return new Goal(goalId, description, nextStatus, priority, metadata);
    }

    public Goal markActive() {
        return withStatus(GoalStatus.ACTIVE);
    }

    public Goal markInProgress() {
        return withStatus(GoalStatus.IN_PROGRESS);
    }

    public Goal markCompleted() {
        return withStatus(GoalStatus.COMPLETED);
    }

    public Goal markFailed() {
        return withStatus(GoalStatus.FAILED);
    }

    private static String normalizeGoalId(String goalId, String description) {
        if (goalId != null && !goalId.isBlank()) {
            return goalId.trim();
        }
        String seed = description == null ? "" : description.trim();
        if (seed.isBlank()) {
            return "goal:" + UUID.randomUUID();
        }
        return "goal:" + Integer.toUnsignedString(seed.hashCode(), 16);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

public record Goal(String goal, double priority) {

    public Goal {
        goal = goal == null ? "" : goal.trim();
        priority = clamp(priority);
    }

    public static Goal of(String goal, double priority) {
        return new Goal(goal, priority);
    }

    public static Goal from(AutonomousGoal goal) {
        if (goal == null) {
            return new Goal("", 0.0);
        }
        return new Goal(goal.title(), goal.priority() / 100.0);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

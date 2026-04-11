package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.util.List;

public interface GoalGenerator {

    List<AutonomousGoal> generate(String userId, int limit);

    default List<Goal> generateGoals(String userId, int limit) {
        return generate(userId, limit).stream().map(AutonomousGoal::toGoal).toList();
    }

    default AutonomousGoal primaryGoal(String userId) {
        List<AutonomousGoal> goals = generate(userId, 1);
        return goals.isEmpty() ? null : goals.get(0);
    }

    default Goal primaryGoalView(String userId) {
        List<Goal> goals = generateGoals(userId, 1);
        return goals.isEmpty() ? null : goals.get(0);
    }
}

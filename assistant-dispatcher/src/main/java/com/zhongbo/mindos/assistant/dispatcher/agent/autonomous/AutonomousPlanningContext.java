package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AutonomousPlanningContext(String userId,
                                        String userInput,
                                        Map<String, Object> profileContext,
                                        GoalMemory goalMemory,
                                        int iteration,
                                        GoalExecutionResult lastResult,
                                        EvaluationResult lastEvaluation,
                                        List<String> excludedTargets) {

    public AutonomousPlanningContext {
        userId = userId == null ? "" : userId.trim();
        userInput = userInput == null ? "" : userInput.trim();
        profileContext = profileContext == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(profileContext));
        iteration = Math.max(1, iteration);
        excludedTargets = excludedTargets == null ? List.of() : List.copyOf(excludedTargets);
    }

    public static AutonomousPlanningContext empty() {
        return new AutonomousPlanningContext("", "", Map.of(), null, 1, null, null, List.of());
    }

    public static AutonomousPlanningContext safe(AutonomousPlanningContext context) {
        return context == null ? empty() : context;
    }

    public AutonomousPlanningContext nextIteration(GoalExecutionResult result,
                                                   EvaluationResult evaluation,
                                                   List<String> nextExcludedTargets) {
        return new AutonomousPlanningContext(
                userId,
                userInput,
                profileContext,
                goalMemory,
                iteration + 1,
                result,
                evaluation,
                nextExcludedTargets
        );
    }
}

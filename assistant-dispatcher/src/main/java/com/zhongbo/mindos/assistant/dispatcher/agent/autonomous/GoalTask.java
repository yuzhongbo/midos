package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GoalTask(String taskId,
                       String description,
                       List<String> dependsOn,
                       Map<String, Object> params,
                       String targetHint,
                       boolean optional,
                       int maxAttempts) {

    public GoalTask {
        taskId = taskId == null ? "" : taskId.trim();
        description = description == null ? "" : description.trim();
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        params = params == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(params));
        targetHint = targetHint == null ? "" : targetHint.trim();
        maxAttempts = Math.max(1, maxAttempts);
    }
}

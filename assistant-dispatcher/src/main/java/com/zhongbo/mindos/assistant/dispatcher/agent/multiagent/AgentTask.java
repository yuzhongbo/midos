package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record AgentTask(String taskId,
                        AgentTaskType type,
                        String userId,
                        String userInput,
                        Map<String, Object> payload) {

    public AgentTask {
        taskId = normalizeId(taskId);
        type = type == null ? AgentTaskType.PLAN_REQUEST : type;
        userId = normalize(userId);
        userInput = normalize(userInput);
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }

    public static AgentTask of(AgentTaskType type,
                               String userId,
                               String userInput,
                               Map<String, Object> payload) {
        return new AgentTask(UUID.randomUUID().toString(), type, userId, userInput, payload);
    }

    public static AgentTask of(String taskId,
                               AgentTaskType type,
                               String userId,
                               String userInput,
                               Map<String, Object> payload) {
        return new AgentTask(taskId, type, userId, userInput, payload);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeId(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? UUID.randomUUID().toString() : normalized;
    }
}

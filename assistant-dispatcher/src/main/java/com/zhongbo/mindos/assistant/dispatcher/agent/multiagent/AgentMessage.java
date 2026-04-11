package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record AgentMessage(String from, String to, Object payload) {

    public AgentMessage {
        from = normalize(from);
        to = normalize(to);
    }

    public static AgentMessage of(String from, String to, Object payload) {
        return new AgentMessage(from, to, payload);
    }

    public static AgentMessage reply(AgentMessage parent, String from, String to, Object payload) {
        return of(from, to, payload);
    }

    public <T> Optional<T> payloadAs(Class<T> type) {
        if (type == null || payload == null || !type.isInstance(payload)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(payload));
    }

    public Optional<AgentTask> task() {
        return payloadAs(AgentTask.class);
    }

    public AgentTaskType type() {
        return task().map(AgentTask::type).orElse(null);
    }

    public String taskId() {
        return task().map(AgentTask::taskId).orElse("");
    }

    public String userId() {
        return task().map(AgentTask::userId).orElse("");
    }

    public String userInput() {
        return task().map(AgentTask::userInput).orElse("");
    }

    public Map<String, Object> payloadMap() {
        if (payload instanceof AgentTask task) {
            return task.payload();
        }
        if (payload instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() == null || String.valueOf(entry.getKey()).isBlank()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
        }
        return Map.of();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
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

    public com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage toNetworkMessage() {
        if (payload instanceof AgentTask task) {
            Map<String, Object> payloadMap = new LinkedHashMap<>(task.payload());
            payloadMap.put("taskId", task.taskId());
            payloadMap.put("userId", task.userId());
            payloadMap.put("userInput", task.userInput());
            return com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage.of(
                    from,
                    to,
                    task.type().name(),
                    payloadMap
            );
        }
        return com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage.of(
                from,
                to,
                inferType(payload),
                payload
        );
    }

    public static AgentMessage fromNetworkMessage(com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage message) {
        if (message == null) {
            return new AgentMessage("", "", null);
        }
        if (message.payload() instanceof AgentTask task) {
            return new AgentMessage(message.from(), message.to(), task);
        }
        AgentTaskType taskType = parseTaskType(message.type());
        if (taskType == null) {
            return new AgentMessage(message.from(), message.to(), message.payload());
        }
        Map<String, Object> payloadMap = message.payloadMap();
        String taskId = firstNonBlank(stringValue(payloadMap.get("taskId")), message.type());
        String userId = stringValue(payloadMap.get("userId"));
        String userInput = stringValue(payloadMap.get("userInput"));
        Map<String, Object> taskPayload = new LinkedHashMap<>(payloadMap);
        taskPayload.remove("taskId");
        taskPayload.remove("userId");
        taskPayload.remove("userInput");
        taskPayload.remove("taskType");
        return new AgentMessage(message.from(), message.to(), AgentTask.of(taskId, taskType, userId, userInput, taskPayload));
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

    private static String inferType(Object value) {
        if (value == null) {
            return "message";
        }
        if (value instanceof String) {
            return "text";
        }
        if (value instanceof Map<?, ?>) {
            return "map";
        }
        return value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static AgentTaskType parseTaskType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace('.', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return AgentTaskType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record AgentMessage(String from, String to, String type, Object payload) {

    public AgentMessage {
        from = normalize(from);
        to = normalize(to);
        type = normalizeType(type);
    }

    public static AgentMessage of(String from, String to, String type, Object payload) {
        return new AgentMessage(from, to, type, payload);
    }

    public static AgentMessage of(String from, String to, Object payload) {
        return new AgentMessage(from, to, inferType(payload), payload);
    }

    public static AgentMessage reply(AgentMessage request, String from, String type, Object payload) {
        return new AgentMessage(from, request == null ? "" : request.from(), type, payload);
    }

    public <T> Optional<T> payloadAs(Class<T> targetType) {
        if (targetType == null || payload == null || !targetType.isInstance(payload)) {
            return Optional.empty();
        }
        return Optional.of(targetType.cast(payload));
    }

    public Map<String, Object> payloadMap() {
        if (payload instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                if (key.isBlank() || entry.getValue() == null) {
                    continue;
                }
                normalized.put(key, entry.getValue());
            }
            return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
        }
        return Map.of();
    }

    public <T> Optional<T> payloadField(String key, Class<T> targetType) {
        if (key == null || key.isBlank() || targetType == null) {
            return Optional.empty();
        }
        Object value = payloadMap().get(key.trim());
        if (value != null && targetType.isInstance(value)) {
            return Optional.of(targetType.cast(value));
        }
        return Optional.empty();
    }

    public boolean isType(String expectedType) {
        return expectedType != null && type.equalsIgnoreCase(expectedType.trim());
    }

    public com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage toMultiAgentMessage() {
        return com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage.of(from, to, payload);
    }

    public static AgentMessage fromMultiAgentMessage(com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage message) {
        if (message == null) {
            return new AgentMessage("", "", "message", null);
        }
        String inferredType = message.type() == null
                ? inferType(message.payload())
                : message.type().name().toLowerCase(Locale.ROOT);
        return new AgentMessage(message.from(), message.to(), inferredType, message.payload());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeType(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "message" : normalized;
    }

    private static String inferType(Object payload) {
        if (payload == null) {
            return "message";
        }
        if (payload instanceof String) {
            return "text";
        }
        if (payload instanceof Map<?, ?>) {
            return "map";
        }
        return payload.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}

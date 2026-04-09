package com.zhongbo.mindos.assistant.dispatcher.decision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

public class DecisionParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<Decision> parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawJson.trim();
        if (!trimmed.startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (!root.isObject()) {
                return Optional.empty();
            }
            String target = text(root, "target");
            if (target == null || target.isBlank()) {
                target = text(root, "skill"); // backwards compatibility with existing prompts
            }
            if (target == null || target.isBlank()) {
                return Optional.empty();
            }

            String intent = text(root, "intent");
            Map<String, Object> params = extractParams(root);
            double confidence = root.path("confidence").isNumber() ? root.get("confidence").asDouble() : 0.0d;
            boolean requiresClarify = root.path("requiresClarify").asBoolean(false);

            return Optional.of(new Decision(intent, target, params, confidence, requiresClarify));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Map<String, Object> extractParams(JsonNode root) {
        JsonNode paramsNode = root.get("params");
        if (paramsNode == null || paramsNode.isMissingNode() || paramsNode.isNull()) {
            paramsNode = root.get("input");
        }
        if (paramsNode != null && paramsNode.isObject()) {
            return objectMapper.convertValue(paramsNode, new TypeReference<>() { });
        }
        return Map.of();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }
}

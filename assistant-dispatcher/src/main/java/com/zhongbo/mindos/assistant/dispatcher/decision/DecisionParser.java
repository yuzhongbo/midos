package com.zhongbo.mindos.assistant.dispatcher.decision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
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
            String target = text(root, "target", true);
            if (target == null || target.isBlank()) {
                return Optional.empty();
            }

            String intent = text(root, "intent", false);
            Map<String, Object> params = extractParams(root).orElse(null);
            if (params == null) {
                return Optional.empty();
            }
            Double confidence = extractConfidence(root);
            if (confidence == null) {
                return Optional.empty();
            }
            Boolean requireClarify = extractRequireClarify(root);
            if (requireClarify == null) {
                return Optional.empty();
            }

            return Optional.of(new Decision(intent, target, params, confidence, requireClarify));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> extractParams(JsonNode root) {
        JsonNode paramsNode = root.get("params");
        if (paramsNode == null || paramsNode.isMissingNode() || paramsNode.isNull()) {
            return Optional.of(Map.of());
        }
        if (!paramsNode.isObject()) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.convertValue(paramsNode, new TypeReference<>() { }));
    }

    private Double extractConfidence(JsonNode root) {
        JsonNode confidenceNode = root.get("confidence");
        if (confidenceNode == null || confidenceNode.isMissingNode() || confidenceNode.isNull()) {
            return 0.0d;
        }
        if (!confidenceNode.isNumber()) {
            return null;
        }
        return confidenceNode.asDouble();
    }

    private Boolean extractRequireClarify(JsonNode root) {
        JsonNode requireClarifyNode = root.get("requireClarify");
        if (requireClarifyNode == null || requireClarifyNode.isMissingNode() || requireClarifyNode.isNull()) {
            return false;
        }
        if (!requireClarifyNode.isBoolean()) {
            return null;
        }
        return requireClarifyNode.asBoolean();
    }

    private String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            return required ? null : value.toString();
        }
        return value.asText();
    }
}

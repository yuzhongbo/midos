package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SimpleParamValidator implements ParamValidator {

    private final ParamSchemaRegistry registry;

    public SimpleParamValidator(ParamSchemaRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ValidationResult validate(String target, Map<String, Object> params) {
        if (target == null || target.isBlank()) {
            return ValidationResult.error("missing target");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        return registry.find(target)
                .map(schema -> validateAgainstSchema(schema, safeParams))
                .orElseGet(ValidationResult::ok);
    }

    private ValidationResult validateAgainstSchema(ParamSchema schema, Map<String, Object> params) {
        ValidationResult missingRequired = validateRequired(schema.required(), params);
        if (!missingRequired.valid()) {
            return missingRequired;
        }
        ValidationResult atLeastOne = validateAtLeastOne(schema.atLeastOne(), params);
        if (!atLeastOne.valid()) {
            return atLeastOne;
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateRequired(Set<String> required, Map<String, Object> params) {
        if (required == null || required.isEmpty()) {
            return ValidationResult.ok();
        }
        Set<String> missing = required.stream()
                .filter(key -> !params.containsKey(key) || isBlank(params.get(key)))
                .collect(Collectors.toSet());
        if (missing.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.error("缺少必填参数: " + String.join(",", missing));
    }

    private ValidationResult validateAtLeastOne(Set<String> candidates, Map<String, Object> params) {
        if (candidates == null || candidates.isEmpty()) {
            return ValidationResult.ok();
        }
        boolean present = candidates.stream().anyMatch(key -> params.containsKey(key) && !isBlank(params.get(key)));
        if (present) {
            return ValidationResult.ok();
        }
        return ValidationResult.error("至少需要提供以下参数之一: " + String.join(",", candidates));
    }

    private boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        String text = String.valueOf(value);
        return text.isBlank();
    }
}

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
                .map(schema -> validateRequired(schema.required(), safeParams))
                .orElseGet(ValidationResult::ok);
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
        return ValidationResult.error("missing required params: " + String.join(",", missing));
    }

    private boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        String text = String.valueOf(value);
        return text.isBlank();
    }
}

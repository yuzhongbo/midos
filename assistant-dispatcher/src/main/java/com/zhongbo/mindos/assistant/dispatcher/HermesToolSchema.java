package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchema;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamType;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class HermesToolSchema {

    private final String name;
    private final String description;
    private final List<String> routingKeywords;
    private final ParamSchema paramSchema;

    private HermesToolSchema(String name,
                             String description,
                             List<String> routingKeywords,
                             ParamSchema paramSchema) {
        this.name = normalize(name);
        this.description = normalize(description);
        this.routingKeywords = routingKeywords == null ? List.of() : List.copyOf(routingKeywords.stream()
                .map(HermesToolSchema::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        this.paramSchema = paramSchema;
    }

    static HermesToolSchema fromDescriptor(SkillDescriptor descriptor, ParamSchema paramSchema) {
        if (descriptor == null) {
            return of("", "", paramSchema);
        }
        return new HermesToolSchema(descriptor.name(), descriptor.description(), descriptor.routingKeywords(), paramSchema);
    }

    static HermesToolSchema of(String name, String description, ParamSchema paramSchema) {
        return new HermesToolSchema(name, description, List.of(), paramSchema);
    }

    String name() {
        return name;
    }

    boolean matches(String target) {
        return !name.isBlank() && name.equals(normalize(target));
    }

    String semanticSummary() {
        StringBuilder summary = new StringBuilder(name);
        if (!description.isBlank()) {
            summary.append(" - ").append(description);
        }
        List<String> details = new ArrayList<>();
        if (!routingKeywords.isEmpty()) {
            details.add("keywords=" + String.join("/", routingKeywords.stream().limit(6).toList()));
        }
        if (paramSchema != null) {
            if (!paramSchema.required().isEmpty()) {
                details.add("required=" + join(paramSchema.required()));
            }
            if (!paramSchema.atLeastOne().isEmpty()) {
                details.add("oneOf=" + join(paramSchema.atLeastOne()));
            }
            if (!paramSchema.types().isEmpty()) {
                details.add("types=" + describeTypes(paramSchema.types()));
            }
            if (!paramSchema.defaults().isEmpty()) {
                details.add("defaults=" + describeDefaults(paramSchema.defaults()));
            }
        }
        if (!details.isEmpty()) {
            summary.append(" | ").append(String.join(" | ", details));
        }
        return summary.toString();
    }

    private static String describeTypes(Map<String, ParamType> types) {
        return types.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().name().toLowerCase())
                .collect(Collectors.joining(","));
    }

    private static String describeDefaults(Map<String, Object> defaults) {
        return defaults.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private static String join(Set<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

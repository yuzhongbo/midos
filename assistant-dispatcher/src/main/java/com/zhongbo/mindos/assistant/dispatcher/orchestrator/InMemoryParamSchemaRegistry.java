package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryParamSchemaRegistry implements ParamSchemaRegistry {

    private final Map<String, ParamSchema> registry = new ConcurrentHashMap<>();
    private final Map<String, ParamSchema> prefixRegistry = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerDefaults() {
        register("todo.create", ParamSchema.atLeastOne("task")
                .withAliases(Map.of("task", java.util.List.of("query"))));
        register("news_search", ParamSchema.atLeastOne("query", "keyword")
                .withAliases(Map.of("query", java.util.List.of("keyword")))
                .withTypes(Map.of("limit", ParamType.INTEGER))
                .withDefaults(Map.of("limit", 5))
                .withNumericRanges(Map.of("limit", ParamSchema.NumericRange.closed(1, 10))));
        register("teaching.plan", ParamSchema.of(Set.of("topic"), Set.of("topic"))
                .withAliases(Map.of(
                        "topic", java.util.List.of("query"),
                        "studentId", java.util.List.of("student", "id"),
                        "durationWeeks", java.util.List.of("weeks"),
                        "weeklyHours", java.util.List.of("hours")
                ))
                .withTypes(Map.of(
                        "studentId", ParamType.STRING,
                        "durationWeeks", ParamType.INTEGER,
                        "weeklyHours", ParamType.INTEGER
                ))
                .withDefaults(Map.of(
                        "durationWeeks", 4,
                        "weeklyHours", 6
                ))
                .withNumericRanges(Map.of(
                        "durationWeeks", ParamSchema.NumericRange.closed(1, 52),
                        "weeklyHours", ParamSchema.NumericRange.closed(1, 80)
                )));
        register("eq.coach", ParamSchema.atLeastOne("query")
                .withAliases(Map.of("query", java.util.List.of("task"))));
        register("code.generate", ParamSchema.atLeastOne("task")
                .withAliases(Map.of("task", java.util.List.of("query"))));
        register("file.search", ParamSchema.atLeastOne("keyword", "path")
                .withAliases(Map.of(
                        "keyword", java.util.List.of("query"),
                        "fileType", java.util.List.of("type")
                ))
                .withTypes(Map.of("limit", ParamType.INTEGER))
                .withDefaults(Map.of("limit", 5))
                .withNumericRanges(Map.of("limit", ParamSchema.NumericRange.closed(1, 20))));
        register("mcp.*", ParamSchema.atLeastOne("input", "query")
                .withAliases(Map.of("query", java.util.List.of("input", "keyword"))));
    }

    public void register(String target, ParamSchema schema) {
        if (target == null || target.isBlank() || schema == null) {
            return;
        }
        if (target.endsWith(".*")) {
            prefixRegistry.put(target.substring(0, target.length() - 1), schema);
            return;
        }
        registry.put(target, schema);
    }

    @Override
    public Optional<ParamSchema> find(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        ParamSchema schema = registry.get(target);
        if (schema != null) {
            return Optional.of(schema);
        }
        return prefixRegistry.entrySet().stream()
                .filter(entry -> target.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}

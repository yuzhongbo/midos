package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryParamSchemaRegistry implements ParamSchemaRegistry {

    private final Map<String, ParamSchema> registry = new ConcurrentHashMap<>();
    private final Map<String, ParamSchema> prefixRegistry = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerDefaults() {
        register("todo.create", ParamSchema.atLeastOne("task", "input"));
        register("news_search", ParamSchema.atLeastOne("query", "keyword", "input"));
        register("teaching.plan", ParamSchema.atLeastOne("topic", "input"));
        register("eq.coach", ParamSchema.atLeastOne("query", "input"));
        register("code.generate", ParamSchema.atLeastOne("task", "input"));
        register("mcp.*", ParamSchema.atLeastOne("input", "query"));
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

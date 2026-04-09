package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryParamSchemaRegistry implements ParamSchemaRegistry {

    private final Map<String, ParamSchema> registry = new ConcurrentHashMap<>();

    public void register(String target, ParamSchema schema) {
        if (target == null || target.isBlank() || schema == null) {
            return;
        }
        registry.put(target, schema);
    }

    @Override
    public Optional<ParamSchema> find(String target) {
        return Optional.ofNullable(registry.get(target));
    }
}

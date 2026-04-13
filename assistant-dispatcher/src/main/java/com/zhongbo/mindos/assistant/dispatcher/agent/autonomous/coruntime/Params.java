package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.util.LinkedHashMap;
import java.util.Map;

public record Params(Map<String, Object> values) {

    public Params {
        values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    public boolean empty() {
        return values.isEmpty();
    }
}

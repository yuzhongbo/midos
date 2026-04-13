package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

public record CognitivePluginContext(Task task,
                                     RuntimeState runtimeState,
                                     AGIMemory memory,
                                     Map<String, Object> attributes) {

    public CognitivePluginContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }
}

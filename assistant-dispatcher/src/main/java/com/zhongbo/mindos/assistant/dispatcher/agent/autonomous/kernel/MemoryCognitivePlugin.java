package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MemoryCognitivePlugin implements CognitivePlugin {

    @Override
    public String pluginId() {
        return "memory.unified";
    }

    @Override
    public CognitiveCapability capability() {
        return CognitiveCapability.MEMORY;
    }

    @Override
    public RuntimeObject runtimeObject() {
        return new RuntimeObject(
                "plugin.memory.unified",
                RuntimeObjectType.COGNITIVE_PLUGIN,
                "memory",
                Map.of("stores", List.of("shortTerm", "longTerm", "semantic"))
        );
    }

    @Override
    public CognitivePluginOutput run(CognitivePluginContext context) {
        Task task = context == null ? null : context.task();
        AGIMemory memory = context == null ? null : context.memory();
        if (task == null || memory == null) {
            return CognitivePluginOutput.empty();
        }
        String namespace = "task:" + task.taskId();
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("memory.shortTerm", memory.shortTerm().get(namespace));
        attributes.put("memory.semanticHits", memory.semantic().search(task.goal().description(), 3));
        attributes.put("memory.longTermNeighbors", memory.longTerm().neighbors(task.goal().goalId()));
        memory.longTerm().link(task.taskId(), task.goal().goalId());
        memory.semantic().put(namespace + ":goal", task.goal().description());
        return new CognitivePluginOutput(
                null,
                attributes,
                0.7,
                "memory-context-loaded"
        );
    }

    @Override
    public int priority() {
        return 5;
    }
}

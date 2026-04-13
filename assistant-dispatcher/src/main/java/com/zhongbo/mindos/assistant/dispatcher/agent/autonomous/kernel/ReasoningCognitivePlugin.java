package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReasoningCognitivePlugin implements CognitivePlugin {

    @Override
    public String pluginId() {
        return "reasoning.default";
    }

    @Override
    public CognitiveCapability capability() {
        return CognitiveCapability.REASONING;
    }

    @Override
    public RuntimeObject runtimeObject() {
        return new RuntimeObject(
                "plugin.reasoning.default",
                RuntimeObjectType.COGNITIVE_PLUGIN,
                "reasoning",
                Map.of("kind", "heuristic-reasoning")
        );
    }

    @Override
    public CognitivePluginOutput run(CognitivePluginContext context) {
        Task task = context == null ? null : context.task();
        if (task == null || task.goal() == null || task.goal().description().isBlank()) {
            return CognitivePluginOutput.empty();
        }
        LinkedHashSet<String> focusAreas = new LinkedHashSet<>();
        for (String token : task.goal().description().toLowerCase(Locale.ROOT).split("[\\s,，。;；]+")) {
            if (!token.isBlank()) {
                focusAreas.add(token.trim());
            }
            if (focusAreas.size() >= 6) {
                break;
            }
        }
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("reasoning.focusAreas", List.copyOf(focusAreas));
        attributes.put("goal.priority", task.goal().priority());
        attributes.put("reasoning.policyHint", task.policy().name().toLowerCase(Locale.ROOT));
        return new CognitivePluginOutput(
                null,
                attributes,
                0.6,
                "reasoning focus=" + focusAreas
        );
    }

    @Override
    public int priority() {
        return 5;
    }
}

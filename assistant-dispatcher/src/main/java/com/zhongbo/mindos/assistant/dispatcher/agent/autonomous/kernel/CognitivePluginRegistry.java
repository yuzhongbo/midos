package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class CognitivePluginRegistry {

    private final Map<CognitiveCapability, CopyOnWriteArrayList<CognitivePlugin>> pluginsByCapability = new EnumMap<>(CognitiveCapability.class);

    @Autowired
    public CognitivePluginRegistry(List<CognitivePlugin> plugins) {
        for (CognitiveCapability capability : CognitiveCapability.values()) {
            pluginsByCapability.put(capability, new CopyOnWriteArrayList<>());
        }
        if (plugins != null) {
            plugins.forEach(this::register);
        }
    }

    public void register(CognitivePlugin plugin) {
        if (plugin == null || plugin.capability() == null) {
            return;
        }
        CopyOnWriteArrayList<CognitivePlugin> plugins = pluginsByCapability.computeIfAbsent(plugin.capability(), ignored -> new CopyOnWriteArrayList<>());
        plugins.removeIf(existing -> existing != null && existing.pluginId().equalsIgnoreCase(plugin.pluginId()));
        plugins.add(plugin);
        plugins.sort(Comparator.comparingInt(CognitivePlugin::priority).reversed().thenComparing(CognitivePlugin::pluginId));
    }

    public void replace(CognitivePlugin plugin) {
        if (plugin == null || plugin.capability() == null) {
            return;
        }
        CopyOnWriteArrayList<CognitivePlugin> plugins = pluginsByCapability.computeIfAbsent(plugin.capability(), ignored -> new CopyOnWriteArrayList<>());
        plugins.clear();
        plugins.add(plugin);
    }

    public CognitivePlugin select(CognitiveCapability capability) {
        List<CognitivePlugin> plugins = plugins(capability);
        return plugins.isEmpty() ? null : plugins.get(0);
    }

    public List<CognitivePlugin> plugins(CognitiveCapability capability) {
        if (capability == null) {
            return List.of();
        }
        return List.copyOf(pluginsByCapability.getOrDefault(capability, new CopyOnWriteArrayList<>()));
    }

    public CognitiveModule moduleSet() {
        return new CognitiveModule(
                select(CognitiveCapability.PREDICTION),
                select(CognitiveCapability.PLANNING),
                select(CognitiveCapability.MEMORY),
                select(CognitiveCapability.REASONING),
                select(CognitiveCapability.TOOL_USE)
        );
    }

    public List<RuntimeObject> runtimeObjects() {
        List<RuntimeObject> objects = new ArrayList<>();
        for (CognitiveCapability capability : CognitiveCapability.values()) {
            plugins(capability).forEach(plugin -> {
                if (plugin != null && plugin.runtimeObject() != null) {
                    objects.add(plugin.runtimeObject());
                }
            });
        }
        return List.copyOf(objects);
    }
}

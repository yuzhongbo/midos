package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.ArrayList;
import java.util.List;

public record CognitiveModule(CognitivePlugin predictionModule,
                              CognitivePlugin planningModule,
                              CognitivePlugin memoryModule,
                              CognitivePlugin reasoningModule,
                              CognitivePlugin toolUseModule) {

    public List<CognitivePlugin> activePlugins() {
        List<CognitivePlugin> plugins = new ArrayList<>();
        if (predictionModule != null) {
            plugins.add(predictionModule);
        }
        if (planningModule != null) {
            plugins.add(planningModule);
        }
        if (memoryModule != null) {
            plugins.add(memoryModule);
        }
        if (reasoningModule != null) {
            plugins.add(reasoningModule);
        }
        if (toolUseModule != null) {
            plugins.add(toolUseModule);
        }
        return List.copyOf(plugins);
    }
}

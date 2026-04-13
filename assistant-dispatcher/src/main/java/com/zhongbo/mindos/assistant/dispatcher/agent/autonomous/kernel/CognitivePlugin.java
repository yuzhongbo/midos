package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

public interface CognitivePlugin {

    String pluginId();

    CognitiveCapability capability();

    RuntimeObject runtimeObject();

    CognitivePluginOutput run(CognitivePluginContext context);

    default int priority() {
        return 0;
    }
}

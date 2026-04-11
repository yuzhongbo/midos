package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

public interface StrategyAgent {

    StrategicGoal strategize(String userId);

    default StrategicGoal generate(String userId) {
        return strategize(userId);
    }
}

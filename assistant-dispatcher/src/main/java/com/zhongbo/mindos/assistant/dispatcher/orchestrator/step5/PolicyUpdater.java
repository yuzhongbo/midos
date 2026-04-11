package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

public interface PolicyUpdater {

    RewardModel update(String userId,
                       String skillName,
                       String routeType,
                       boolean success,
                       long latencyMs,
                       int tokenEstimate,
                       boolean usedFallback);
}

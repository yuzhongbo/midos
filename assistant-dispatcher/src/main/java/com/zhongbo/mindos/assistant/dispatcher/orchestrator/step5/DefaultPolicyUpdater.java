package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DefaultPolicyUpdater implements PolicyUpdater {

    private final PlannerLearningStore plannerLearningStore;
    private final long highLatencyThresholdMs;

    public DefaultPolicyUpdater(PlannerLearningStore plannerLearningStore,
                                MemoryGateway memoryGateway,
                                @Value("${mindos.dispatcher.step5.policy.high-latency-ms:1800}") long highLatencyThresholdMs) {
        this.plannerLearningStore = plannerLearningStore;
        this.highLatencyThresholdMs = Math.max(1L, highLatencyThresholdMs);
    }

    @Override
    public RewardModel update(String userId,
                              String skillName,
                              String routeType,
                              boolean success,
                              long latencyMs,
                              int tokenEstimate,
                              boolean usedFallback) {
        RewardModel reward = RewardModel.evaluate(skillName, routeType, success, latencyMs, tokenEstimate, usedFallback, highLatencyThresholdMs);
        if (plannerLearningStore != null && hasText(userId) && hasText(skillName)) {
            plannerLearningStore.observe(userId, skillName, routeType, success, latencyMs, tokenEstimate, usedFallback, reward.reward());
        }
        return reward;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

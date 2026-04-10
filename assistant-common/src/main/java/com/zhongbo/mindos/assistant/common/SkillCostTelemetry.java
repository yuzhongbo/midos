package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dto.CostModel;

import java.util.Map;

public interface SkillCostTelemetry {

    void record(String userId, String skillName, long latencyMs, int totalTokensEstimate, boolean success);

    Map<String, CostModel> costModels(String userId);

    default Map<String, Double> averageLatencies(String userId) {
        return Map.of();
    }
}

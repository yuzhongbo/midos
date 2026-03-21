package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record LlmCallMetricDto(
        Instant timestamp,
        String userId,
        String provider,
        String endpoint,
        String routeStage,
        boolean success,
        boolean retried,
        long latencyMs,
        int promptTokensEstimate,
        int responseTokensEstimate,
        int totalTokensEstimate,
        String errorType
) {
}


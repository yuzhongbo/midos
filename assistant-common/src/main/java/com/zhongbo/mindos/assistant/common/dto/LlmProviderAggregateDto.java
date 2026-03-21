package com.zhongbo.mindos.assistant.common.dto;

public record LlmProviderAggregateDto(
        String provider,
        long calls,
        long successCount,
        long failureCount,
        long retryCount,
        long fallbackCount,
        double successRate,
        double fallbackRate,
        double avgLatencyMs,
        long totalEstimatedTokens
) {
}


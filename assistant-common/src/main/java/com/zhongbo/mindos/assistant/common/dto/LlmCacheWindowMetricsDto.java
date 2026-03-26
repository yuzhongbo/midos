package com.zhongbo.mindos.assistant.common.dto;

public record LlmCacheWindowMetricsDto(
        long hits,
        long misses,
        double hitRate
) {
}


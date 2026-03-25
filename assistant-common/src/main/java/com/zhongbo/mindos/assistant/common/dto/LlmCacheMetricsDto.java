package com.zhongbo.mindos.assistant.common.dto;

public record LlmCacheMetricsDto(
        boolean enabled,
        long hitCount,
        long missCount,
        double hitRate,
        long entryCount,
        long ttlSeconds,
        int maxEntries
) {
}


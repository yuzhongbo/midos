package com.zhongbo.mindos.assistant.common.dto;

public record MemoryHitMetricsDto(
        long requests,
        long semanticHits,
        long proceduralHits,
        long rollupHits,
        double approximateHitRate
) {
}


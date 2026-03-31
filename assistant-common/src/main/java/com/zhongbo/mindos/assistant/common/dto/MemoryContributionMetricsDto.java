package com.zhongbo.mindos.assistant.common.dto;

public record MemoryContributionMetricsDto(
        long requests,
        long recentTagged,
        long semanticTagged,
        long proceduralTagged,
        long personaTagged,
        long rollupTagged
) {
}


package com.zhongbo.mindos.assistant.common.dto;

public record ContextCompressionMetricsDto(
        long requests,
        long compressedRequests,
        long totalInputChars,
        long totalOutputChars,
        double avgCompressionRatio,
        long summarizedTurns
) {
}


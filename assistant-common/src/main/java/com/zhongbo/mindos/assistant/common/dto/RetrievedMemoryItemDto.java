package com.zhongbo.mindos.assistant.common.dto;

public record RetrievedMemoryItemDto(
        String type,
        String text,
        double relevanceScore,
        double recencyScore,
        double reliabilityScore,
        double finalScore,
        long createdAtEpochMs
) {
}


package com.zhongbo.mindos.assistant.common.dto;

public record LongTaskAutoRunResultDto(
        String userId,
        String workerId,
        int claimedCount,
        int advancedCount,
        int completedCount
) {
}


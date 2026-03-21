package com.zhongbo.mindos.assistant.common.dto;

public record PendingPreferenceOverrideDto(
        String field,
        String pendingValue,
        int count,
        int confirmThreshold,
        int remainingConfirmTurns
) {
}


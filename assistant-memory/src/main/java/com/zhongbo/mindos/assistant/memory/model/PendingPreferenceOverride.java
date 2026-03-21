package com.zhongbo.mindos.assistant.memory.model;

public record PendingPreferenceOverride(
        String field,
        String pendingValue,
        int count,
        int confirmThreshold,
        int remainingConfirmTurns
) {
}


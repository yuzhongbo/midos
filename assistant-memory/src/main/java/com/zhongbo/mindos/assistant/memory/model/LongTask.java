package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;
import java.util.List;

public record LongTask(
        String taskId,
        String userId,
        String title,
        String objective,
        LongTaskStatus status,
        int progressPercent,
        List<String> pendingSteps,
        List<String> completedSteps,
        List<String> recentNotes,
        String blockedReason,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Instant nextCheckAt,
        String leaseOwner,
        Instant leaseUntil
) {
}


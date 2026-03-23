package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record LongTaskDto(
        String taskId,
        String userId,
        String title,
        String objective,
        String status,
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


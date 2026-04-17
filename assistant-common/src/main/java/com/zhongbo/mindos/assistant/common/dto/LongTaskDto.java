package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record LongTaskDto(
        String taskId,
        String userId,
        String title,
        String objective,
        String goalId,
        String parentTaskId,
        List<String> childTaskIds,
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

    public LongTaskDto(String taskId,
                       String userId,
                       String title,
                       String objective,
                       String goalId,
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
                       Instant leaseUntil) {
        this(
                taskId,
                userId,
                title,
                objective,
                goalId,
                "",
                List.of(),
                status,
                progressPercent,
                pendingSteps,
                completedSteps,
                recentNotes,
                blockedReason,
                createdAt,
                updatedAt,
                dueAt,
                nextCheckAt,
                leaseOwner,
                leaseUntil
        );
    }
}

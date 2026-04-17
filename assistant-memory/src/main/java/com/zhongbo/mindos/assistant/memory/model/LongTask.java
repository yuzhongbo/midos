package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;
import java.util.List;

public record LongTask(
        String taskId,
        String userId,
        String title,
        String objective,
        String goalId,
        String parentTaskId,
        List<String> childTaskIds,
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

    public LongTask {
        goalId = goalId == null ? "" : goalId.trim();
        parentTaskId = parentTaskId == null ? "" : parentTaskId.trim();
        childTaskIds = childTaskIds == null ? List.of() : List.copyOf(childTaskIds);
        pendingSteps = pendingSteps == null ? List.of() : List.copyOf(pendingSteps);
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        recentNotes = recentNotes == null ? List.of() : List.copyOf(recentNotes);
        blockedReason = blockedReason == null ? "" : blockedReason.trim();
        leaseOwner = leaseOwner == null ? "" : leaseOwner.trim();
    }

    public LongTask(String taskId,
                    String userId,
                    String title,
                    String objective,
                    String goalId,
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

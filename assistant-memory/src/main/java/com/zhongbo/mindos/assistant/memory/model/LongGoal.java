package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;
import java.util.List;

public record LongGoal(
        String goalId,
        String userId,
        String title,
        String objective,
        String successCriteria,
        LongGoalStatus status,
        int progressPercent,
        List<String> linkedTaskIds,
        List<String> recentNotes,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Instant nextReviewAt
) {

    public LongGoal {
        goalId = goalId == null ? "" : goalId.trim();
        userId = userId == null ? "" : userId.trim();
        title = title == null ? "" : title.trim();
        objective = objective == null ? "" : objective.trim();
        successCriteria = successCriteria == null ? "" : successCriteria.trim();
        status = status == null ? LongGoalStatus.ACTIVE : status;
        progressPercent = Math.max(0, Math.min(100, progressPercent));
        linkedTaskIds = linkedTaskIds == null ? List.of() : List.copyOf(linkedTaskIds);
        recentNotes = recentNotes == null ? List.of() : List.copyOf(recentNotes);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        nextReviewAt = nextReviewAt == null ? createdAt : nextReviewAt;
    }
}

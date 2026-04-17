package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record LongGoalDto(
        String goalId,
        String userId,
        String title,
        String objective,
        String successCriteria,
        String status,
        int progressPercent,
        List<String> linkedTaskIds,
        List<String> recentNotes,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Instant nextReviewAt
) {
}

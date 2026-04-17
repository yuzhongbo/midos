package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record LongGoalCreateRequestDto(
        String title,
        String objective,
        String successCriteria,
        Instant dueAt,
        Instant nextReviewAt
) {
}

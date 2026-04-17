package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record LongGoalStatusUpdateDto(
        String status,
        String note,
        Instant nextReviewAt
) {
}

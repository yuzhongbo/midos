package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record PlanStepDto(
        String stepName,
        String status,
        String channel,
        String note,
        Instant startedAt,
        Instant finishedAt
) {
}


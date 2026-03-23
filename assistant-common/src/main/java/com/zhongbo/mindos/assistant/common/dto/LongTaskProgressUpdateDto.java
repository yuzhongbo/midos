package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record LongTaskProgressUpdateDto(
        String workerId,
        String completedStep,
        String note,
        String blockedReason,
        Instant nextCheckAt,
        boolean markCompleted
) {
}


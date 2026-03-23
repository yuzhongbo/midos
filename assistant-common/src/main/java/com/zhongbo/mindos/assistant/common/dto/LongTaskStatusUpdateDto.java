package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record LongTaskStatusUpdateDto(
        String status,
        String note,
        Instant nextCheckAt
) {
}


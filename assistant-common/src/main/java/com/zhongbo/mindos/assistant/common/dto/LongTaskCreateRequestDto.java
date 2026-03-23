package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record LongTaskCreateRequestDto(
        String title,
        String objective,
        List<String> steps,
        Instant dueAt,
        Instant nextCheckAt
) {
}


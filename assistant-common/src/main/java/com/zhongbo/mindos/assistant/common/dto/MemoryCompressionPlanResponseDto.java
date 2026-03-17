package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record MemoryCompressionPlanResponseDto(
        MemoryStyleProfileDto style,
        List<MemoryCompressionStepDto> steps,
        Instant createdAt
) {
}


package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record LongTaskSplitRequestDto(
        String workerId,
        List<String> childSteps,
        String note,
        Instant nextCheckAt
) {
}

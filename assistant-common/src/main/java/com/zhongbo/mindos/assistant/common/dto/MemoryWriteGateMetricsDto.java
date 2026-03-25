package com.zhongbo.mindos.assistant.common.dto;

public record MemoryWriteGateMetricsDto(
        boolean secondaryDuplicateGateEnabled,
        long secondaryDuplicateChecks,
        long secondaryDuplicateIntercepted,
        double secondaryDuplicateInterceptRate
) {
}


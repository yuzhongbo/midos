package com.zhongbo.mindos.assistant.common.dto;

public record SecurityAuditWriteMetricsDto(
        int queueDepth,
        int queueRemainingCapacity,
        long enqueuedCount,
        long writtenCount,
        long callerRunsFallbackCount,
        long flushTimeoutCount,
        long flushErrorCount
) {
}


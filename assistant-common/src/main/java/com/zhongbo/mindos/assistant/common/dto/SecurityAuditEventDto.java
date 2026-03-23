package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record SecurityAuditEventDto(
        Instant timestamp,
        String traceId,
        String actor,
        String operation,
        String resource,
        String result,
        String reason,
        String remoteAddress,
        String userAgent
) {
}


package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record SecurityAuditQueryResponseDto(
        List<SecurityAuditEventDto> items,
        int limit,
        String cursor,
        String nextCursor,
        String nextCursorExpiresAt,
        String cursorKeyVersion,
        String actor,
        String operation,
        String result,
        String traceId,
        String from,
        String to
) {
}


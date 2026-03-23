package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record SecurityChallengeResponseDto(
        String token,
        String operation,
        String resource,
        String actor,
        Instant expiresAt
) {
}


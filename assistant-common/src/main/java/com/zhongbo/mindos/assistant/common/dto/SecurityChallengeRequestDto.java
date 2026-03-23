package com.zhongbo.mindos.assistant.common.dto;

public record SecurityChallengeRequestDto(
        String operation,
        String resource,
        String actor,
        Long ttlSeconds
) {
}


package com.zhongbo.mindos.assistant.common.dto;

public record PersonaProfileDto(
        String assistantName,
        String role,
        String style,
        String language,
        String timezone,
        String preferredChannel
) {
}


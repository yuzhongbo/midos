package com.zhongbo.mindos.assistant.common.dto;

public record AssistantProfileDto(
        String assistantName,
        String role,
        String style,
        String language,
        String timezone,
        String llmProvider
) {
}


package com.zhongbo.mindos.assistant.cli;

public record AssistantProfile(
        String assistantName,
        String role,
        String style,
        String language,
        String timezone,
        String llmProvider,
        String llmPreset
) {
}


package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;
import java.util.Map;

public record PromptMemoryContextDto(
        String recentConversation,
        String semanticContext,
        String proceduralHints,
        Map<String, Object> personaSnapshot,
        List<RetrievedMemoryItemDto> debugTopItems
) {
}


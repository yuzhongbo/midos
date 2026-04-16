package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;
import java.util.Map;

public record PromptMemoryContextDto(
        String recentConversation,
        String semanticContext,
        String proceduralHints,
        Map<String, Object> personaSnapshot,
        List<RetrievedMemoryItemDto> debugTopItems,
        TaskThreadSnapshotDto taskThreadSnapshot,
        Map<String, Object> learnedPreferences
) {

    public PromptMemoryContextDto(String recentConversation,
                                  String semanticContext,
                                  String proceduralHints,
                                  Map<String, Object> personaSnapshot,
                                  List<RetrievedMemoryItemDto> debugTopItems) {
        this(recentConversation,
                semanticContext,
                proceduralHints,
                personaSnapshot,
                debugTopItems,
                TaskThreadSnapshotDto.empty(),
                Map.of());
    }
}

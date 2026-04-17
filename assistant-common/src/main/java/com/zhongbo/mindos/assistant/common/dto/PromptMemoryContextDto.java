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
        ActiveGoalSnapshotDto activeGoalSnapshot,
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
                ActiveGoalSnapshotDto.empty(),
                Map.of());
    }

    public PromptMemoryContextDto(String recentConversation,
                                  String semanticContext,
                                  String proceduralHints,
                                  Map<String, Object> personaSnapshot,
                                  List<RetrievedMemoryItemDto> debugTopItems,
                                  TaskThreadSnapshotDto taskThreadSnapshot,
                                  Map<String, Object> learnedPreferences) {
        this(recentConversation,
                semanticContext,
                proceduralHints,
                personaSnapshot,
                debugTopItems,
                taskThreadSnapshot,
                ActiveGoalSnapshotDto.empty(),
                learnedPreferences);
    }
}

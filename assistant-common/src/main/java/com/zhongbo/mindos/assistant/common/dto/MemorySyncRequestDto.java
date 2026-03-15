package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record MemorySyncRequestDto(
        String eventId,
        List<ConversationTurnDto> episodic,
        List<SemanticMemoryEntryDto> semantic,
        List<ProceduralMemoryEntryDto> procedural
) {

    public MemorySyncRequestDto {
        episodic = episodic == null ? List.of() : List.copyOf(episodic);
        semantic = semantic == null ? List.of() : List.copyOf(semantic);
        procedural = procedural == null ? List.of() : List.copyOf(procedural);
    }
}


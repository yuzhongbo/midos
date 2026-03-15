package com.zhongbo.mindos.assistant.api.dto;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

import java.util.List;

public record MemorySyncResponse(
        long cursor,
        int acceptedCount,
        int skippedCount,
        List<ConversationTurn> episodic,
        List<SemanticMemoryEntry> semantic,
        List<ProceduralMemoryEntry> procedural
) {

    public MemorySyncResponse {
        episodic = episodic == null ? List.of() : List.copyOf(episodic);
        semantic = semantic == null ? List.of() : List.copyOf(semantic);
        procedural = procedural == null ? List.of() : List.copyOf(procedural);
    }
}


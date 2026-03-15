package com.zhongbo.mindos.assistant.memory.model;

import java.util.List;

public record MemorySyncBatch(
        String eventId,
        List<ConversationTurn> episodic,
        List<SemanticMemoryEntry> semantic,
        List<ProceduralMemoryEntry> procedural
) {

    public MemorySyncBatch {
        episodic = episodic == null ? List.of() : List.copyOf(episodic);
        semantic = semantic == null ? List.of() : List.copyOf(semantic);
        procedural = procedural == null ? List.of() : List.copyOf(procedural);
    }

    public static MemorySyncBatch empty() {
        return new MemorySyncBatch(null, List.of(), List.of(), List.of());
    }
}


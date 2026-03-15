package com.zhongbo.mindos.assistant.memory.model;

import java.util.List;

public record MemorySyncSnapshot(
        long cursor,
        List<ConversationTurn> episodic,
        List<SemanticMemoryEntry> semantic,
        List<ProceduralMemoryEntry> procedural
) {

    public MemorySyncSnapshot {
        episodic = episodic == null ? List.of() : List.copyOf(episodic);
        semantic = semantic == null ? List.of() : List.copyOf(semantic);
        procedural = procedural == null ? List.of() : List.copyOf(procedural);
    }

    public static MemorySyncSnapshot empty(long cursor) {
        return new MemorySyncSnapshot(cursor, List.of(), List.of(), List.of());
    }
}


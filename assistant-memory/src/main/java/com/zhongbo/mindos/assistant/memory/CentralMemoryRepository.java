package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

public interface CentralMemoryRepository {

    MemoryAppendResult appendEpisodic(String userId, ConversationTurn turn, String eventId);

    MemoryAppendResult appendSemantic(String userId, SemanticMemoryEntry entry, String eventId);

    MemoryAppendResult appendProcedural(String userId, ProceduralMemoryEntry entry, String eventId);

    MemorySyncSnapshot fetchSince(String userId, long sinceCursorExclusive, int limit);
}


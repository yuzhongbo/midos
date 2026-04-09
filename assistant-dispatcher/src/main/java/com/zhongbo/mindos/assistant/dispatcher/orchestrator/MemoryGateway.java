package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

import java.util.List;

public interface MemoryGateway {

    List<ConversationTurn> recentHistory(String userId);

    void writeProcedural(ProceduralMemoryEntry entry);

    void writeSemantic(SemanticMemoryEntry entry);
}

package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

import java.util.List;

public interface MemoryGateway {

    List<ConversationTurn> recentHistory(String userId);

    void recordSkillUsage(String userId, String skillName, String input, boolean success);

    void writeProcedural(String userId, ProceduralMemoryEntry entry);

    void writeSemantic(String userId, SemanticMemoryEntry entry);
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultMemoryGateway implements MemoryGateway {

    private final MemoryManager memoryManager;

    public DefaultMemoryGateway(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public List<ConversationTurn> recentHistory(String userId) {
        return List.of();
    }

    @Override
    public void writeProcedural(ProceduralMemoryEntry entry) {
        // placeholder: centralized writes can be wired here
    }

    @Override
    public void writeSemantic(SemanticMemoryEntry entry) {
        // placeholder: centralized writes can be wired here
    }
}

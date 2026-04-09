package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultMemoryGateway implements MemoryGateway {

    private final MemoryManager memoryManager;
    private final int historyWindow;

    public DefaultMemoryGateway(MemoryManager memoryManager) {
        this(memoryManager, 12);
    }

    public DefaultMemoryGateway(MemoryManager memoryManager,
                                @Value("${mindos.dispatcher.memory.history.recent-turns:12}") int historyWindow) {
        this.memoryManager = memoryManager;
        this.historyWindow = Math.max(0, historyWindow);
    }

    @Override
    public List<ConversationTurn> recentHistory(String userId) {
        if (userId == null || userId.isBlank() || historyWindow <= 0) {
            return List.of();
        }
        return memoryManager.getRecentConversation(userId, historyWindow);
    }

    @Override
    public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        memoryManager.logSkillUsage(userId, skillName, input, success);
    }

    @Override
    public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
        if (userId == null || userId.isBlank() || entry == null) {
            return;
        }
        if (entry.skillName() == null || entry.skillName().isBlank()) {
            return;
        }
        memoryManager.logSkillUsage(userId, entry.skillName(), entry.input(), entry.success());
    }

    @Override
    public void writeSemantic(String userId, SemanticMemoryEntry entry) {
        writeSemantic(userId, entry == null ? null : entry.text(), entry == null ? null : entry.embedding(), null);
    }

    @Override
    public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        memoryManager.storeKnowledge(userId, text, embedding, bucket);
    }
}

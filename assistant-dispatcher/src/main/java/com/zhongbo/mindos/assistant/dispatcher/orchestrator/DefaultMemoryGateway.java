package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class DefaultMemoryGateway implements MemoryGateway {

    private final MemoryFacade memoryFacade;
    private final int historyWindow;

    public DefaultMemoryGateway(MemoryFacade memoryFacade) {
        this(memoryFacade, 12);
    }

    @Autowired
    public DefaultMemoryGateway(MemoryFacade memoryFacade,
                                @Value("${mindos.dispatcher.memory.history.recent-turns:12}") int historyWindow) {
        this.memoryFacade = memoryFacade;
        this.historyWindow = Math.max(0, historyWindow);
    }

    @Override
    public List<ConversationTurn> recentHistory(String userId) {
        if (userId == null || userId.isBlank() || historyWindow <= 0) {
            return List.of();
        }
        return memoryFacade.getRecentConversation(userId, historyWindow);
    }

    @Override
    public List<SkillUsageStats> skillUsageStats(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return memoryFacade.getSkillUsageStats(userId);
    }

    @Override
    public void appendUserConversation(String userId, String message) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        memoryFacade.storeUserConversation(userId, message);
    }

    @Override
    public void appendAssistantConversation(String userId, String message) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        memoryFacade.storeAssistantConversation(userId, message);
    }

    @Override
    public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        memoryFacade.logSkillUsage(userId, skillName, input, success);
    }

    @Override
    public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
        if (userId == null || userId.isBlank() || entry == null) {
            return;
        }
        if (entry.skillName() == null || entry.skillName().isBlank()) {
            return;
        }
        memoryFacade.logSkillUsage(userId, entry.skillName(), entry.input(), entry.success());
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
        memoryFacade.storeKnowledge(userId, text, embedding, bucket);
    }

    @Override
    public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
        if (userId == null || userId.isBlank()) {
            return PreferenceProfile.empty();
        }
        return memoryFacade.updatePreferenceProfile(userId, profile);
    }

    @Override
    public LongTask createLongTask(String userId,
                                   String title,
                                   String objective,
                                   List<String> steps,
                                   Instant dueAt,
                                   Instant nextCheckAt) {
        return memoryFacade.createLongTask(userId, title, objective, steps, dueAt, nextCheckAt);
    }

    @Override
    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String completedStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompleted) {
        return memoryFacade.updateLongTaskProgress(
                userId,
                taskId,
                workerId,
                completedStep,
                note,
                blockedReason,
                nextCheckAt,
                markCompleted
        );
    }

    @Override
    public LongTask updateLongTaskStatus(String userId,
                                         String taskId,
                                         LongTaskStatus status,
                                         String note,
                                         Instant nextCheckAt) {
        return memoryFacade.updateLongTaskStatus(userId, taskId, status, note, nextCheckAt);
    }
}

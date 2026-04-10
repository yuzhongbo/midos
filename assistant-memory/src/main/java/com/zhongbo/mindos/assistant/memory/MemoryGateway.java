package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;

import java.time.Instant;
import java.util.List;

public interface MemoryGateway {

    List<ConversationTurn> recentHistory(String userId);

    List<SkillUsageStats> skillUsageStats(String userId);

    void appendUserConversation(String userId, String message);

    void appendAssistantConversation(String userId, String message);

    void recordSkillUsage(String userId, String skillName, String input, boolean success);

    void writeProcedural(String userId, ProceduralMemoryEntry entry);

    void writeSemantic(String userId, SemanticMemoryEntry entry);

    void writeSemantic(String userId, String text, List<Double> embedding, String bucket);

    PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile);

    LongTask createLongTask(String userId,
                            String title,
                            String objective,
                            List<String> steps,
                            Instant dueAt,
                            Instant nextCheckAt);

    LongTask updateLongTaskProgress(String userId,
                                    String taskId,
                                    String workerId,
                                    String completedStep,
                                    String note,
                                    String blockedReason,
                                    Instant nextCheckAt,
                                    boolean markCompleted);

    LongTask updateLongTaskStatus(String userId,
                                  String taskId,
                                  LongTaskStatus status,
                                  String note,
                                  Instant nextCheckAt);
}

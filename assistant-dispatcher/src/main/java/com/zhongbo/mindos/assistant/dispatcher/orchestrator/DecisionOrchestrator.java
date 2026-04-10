package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface DecisionOrchestrator {

    SkillResult execute(String userInput, String intent, Map<String, Object> params);

    OrchestrationOutcome orchestrate(Decision decision, OrchestrationRequest request);

    default OrchestrationOutcome fastPath(Decision decision, OrchestrationRequest request) {
        return orchestrate(decision, request);
    }

    default OrchestrationOutcome slowPath(Decision decision, OrchestrationRequest request) {
        return orchestrate(decision, request);
    }

    void recordOutcome(String userId, String userInput, SkillResult result, ExecutionTraceDto trace);

    void appendUserConversation(String userId, String message);

    void appendAssistantConversation(String userId, String message);

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

    record OrchestrationOutcome(SkillResult result,
                                SkillDsl skillDsl,
                                SkillResult clarification,
                                ExecutionTraceDto trace,
                                String selectedSkill,
                                boolean usedFallback) {
        public boolean hasResult() {
            return result != null;
        }

        public boolean hasClarification() {
            return clarification != null;
        }

        public boolean hasSkillDsl() {
            return skillDsl != null;
        }
    }

    record OrchestrationRequest(String userId,
                                String userInput,
                                SkillContext skillContext,
                                Map<String, Object> profileContext) {
        public Map<String, Object> safeProfileContext() {
            return profileContext == null ? Map.of() : profileContext;
        }
    }
}

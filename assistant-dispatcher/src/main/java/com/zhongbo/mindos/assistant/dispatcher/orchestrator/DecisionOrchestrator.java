package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;

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

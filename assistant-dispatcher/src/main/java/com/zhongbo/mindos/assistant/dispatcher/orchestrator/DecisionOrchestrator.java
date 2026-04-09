package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.Map;

public interface DecisionOrchestrator {

    OrchestrationOutcome orchestrate(Decision decision, Map<String, Object> profileContext);

    record OrchestrationOutcome(SkillDsl skillDsl, com.zhongbo.mindos.assistant.common.SkillResult clarification) {
        public boolean hasSkillDsl() {
            return skillDsl != null;
        }

        public boolean hasClarification() {
            return clarification != null;
        }
    }
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DefaultDecisionPlanner implements DecisionPlanner {

    @Override
    public Decision plan(String userInput, String intent, Map<String, Object> params, SkillContext context) {
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        String target = resolveTarget(intent, safeParams);
        double confidence = hasTarget(target) ? 1.0 : 0.0;
        return new Decision(intent == null ? "" : intent, target, safeParams, confidence, false);
    }

    private String resolveTarget(String intent, Map<String, Object> params) {
        if (params != null) {
            Object explicitTarget = params.get("_target");
            if (explicitTarget instanceof String target && hasTarget(target)) {
                return target.trim();
            }
            Object paramTarget = params.get("target");
            if ((intent == null || intent.isBlank()) && paramTarget instanceof String target && hasTarget(target)) {
                return target.trim();
            }
        }
        return intent == null ? "" : intent;
    }

    private boolean hasTarget(String target) {
        return target != null && !target.isBlank();
    }
}

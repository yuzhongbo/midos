package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class DecisionParamAssembler {

    Decision toDecision(SkillDsl dsl, SkillContext context, double confidence) {
        if (dsl == null) {
            return null;
        }
        Map<String, Object> params = dsl.input() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dsl.input());
        if (context != null && context.input() != null && !context.input().isBlank()) {
            params.putIfAbsent("input", context.input());
        }
        return new Decision(null, dsl.skill(), params, confidence, false);
    }

    Decision toDecision(String skillName, SkillContext context, double confidence) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        return new Decision(null, skillName, decisionParamsFromContext(context), confidence, false);
    }

    Map<String, Object> decisionParamsFromContext(SkillContext context) {
        if (context == null || context.attributes() == null || context.attributes().isEmpty()) {
            return context == null || context.input() == null || context.input().isBlank()
                    ? Map.of()
                    : Map.of("input", context.input());
        }
        Map<String, Object> params = new LinkedHashMap<>(context.attributes());
        if (context.input() != null && !context.input().isBlank()) {
            params.putIfAbsent("input", context.input());
        }
        return params;
    }

    Map<String, Object> orchestratorProfileContext(SkillContext context) {
        if (context == null || context.attributes() == null || context.attributes().isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(context.attributes());
    }

    String resolveSelectedTarget(Decision decision,
                                 DecisionOrchestrator.OrchestrationOutcome outcome,
                                 Optional<SkillResult> routedResult) {
        if (outcome != null && outcome.selectedSkill() != null && !outcome.selectedSkill().isBlank()) {
            return outcome.selectedSkill();
        }
        if (routedResult != null && routedResult.isPresent()) {
            return routedResult.get().skillName();
        }
        return decision == null ? "" : decision.target();
    }
}

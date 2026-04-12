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

    private final SkillCommandAssembler skillCommandAssembler;

    DecisionParamAssembler() {
        this(null);
    }

    DecisionParamAssembler(SkillCommandAssembler skillCommandAssembler) {
        this.skillCommandAssembler = skillCommandAssembler;
    }

    Decision toDecision(SkillDsl dsl, SkillContext context, double confidence) {
        if (dsl == null) {
            return null;
        }
        Map<String, Object> params = dsl.input() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dsl.input());
        return new Decision(null, dsl.skill(), params, confidence, false);
    }

    Decision toDecision(String skillName, SkillContext context, double confidence) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        return new Decision(null, skillName, decisionParamsFromContext(skillName, context), confidence, false);
    }

    Map<String, Object> decisionParamsFromContext(SkillContext context) {
        return decisionParamsFromContext(null, context);
    }

    Map<String, Object> decisionParamsFromContext(String skillName, SkillContext context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>(context.attributes() == null ? Map.of() : context.attributes());
        if (usesCanonicalCommands(skillName)) {
            params.remove("input");
            if (skillCommandAssembler != null) {
                return skillCommandAssembler.buildDetectedSkillDsl(skillName, context.input(), params)
                        .map(SkillDsl::input)
                        .map(input -> (Map<String, Object>) new LinkedHashMap<>(input))
                        .orElse(params);
            }
            return params;
        }
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

    private boolean usesCanonicalCommands(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return switch (skillName) {
            case "teaching.plan", "todo.create", "eq.coach", "file.search", "news_search", "code.generate", "echo", "time" -> true;
            default -> false;
        };
    }
}

package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.List;

final class SemanticClarifyPolicy {

    private static final double DEFAULT_CLARIFY_CONFIDENCE_THRESHOLD = 0.70;

    private final ParamValidator paramValidator;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final double semanticAnalysisClarifyMinConfidence;

    SemanticClarifyPolicy(ParamValidator paramValidator,
                          BehaviorRoutingSupport behaviorRoutingSupport,
                          double semanticAnalysisClarifyMinConfidence) {
        this.paramValidator = paramValidator;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.semanticAnalysisClarifyMinConfidence = semanticAnalysisClarifyMinConfidence;
    }

    boolean shouldAskSemanticClarification(SemanticAnalysisResult semanticAnalysis,
                                           String input,
                                           SemanticRoutingSupport.SemanticRoutingPlan semanticPlan) {
        if (semanticAnalysis == null || semanticPlan == null || semanticPlan.skillName().isBlank()) {
            return false;
        }
        if (behaviorRoutingSupport.isContinuationIntent(normalize(input))) {
            return false;
        }
        double threshold = semanticAnalysisClarifyMinConfidence > 0.0
                ? semanticAnalysisClarifyMinConfidence
                : DEFAULT_CLARIFY_CONFIDENCE_THRESHOLD;
        boolean lowConfidence = semanticPlan.confidence() > 0.0 && semanticPlan.confidence() < threshold;
        boolean missingRequiredParams = !missingRequiredParamsForSkill(
                semanticPlan.skillName(),
                semanticPlan.effectivePayload()
        ).isEmpty();
        return lowConfidence || missingRequiredParams;
    }

    String buildSemanticClarifyReply(SemanticAnalysisResult semanticAnalysis,
                                     SemanticRoutingSupport.SemanticRoutingPlan semanticPlan) {
        String skill = semanticPlan == null ? "" : normalizeOptional(semanticPlan.skillName());
        List<String> missing = semanticPlan == null ? List.of() : missingRequiredParamsForSkill(skill, semanticPlan.effectivePayload());
        StringBuilder reply = new StringBuilder("我理解你想执行");
        reply.append(skill.isBlank() ? "相关操作" : " `" + skill + "`");
        if (semanticAnalysis != null && semanticAnalysis.summary() != null && !semanticAnalysis.summary().isBlank()) {
            reply.append("（").append(capText(semanticAnalysis.summary(), 80)).append("）");
        }
        reply.append("，但我还需要补充一点信息：");
        if (missing.isEmpty()) {
            reply.append("请确认你的目标和关键参数（例如对象、时间、范围）。");
        } else {
            reply.append("请补充 ").append(String.join("、", missing)).append("。");
        }
        return reply.toString();
    }

    private List<String> missingRequiredParamsForSkill(String skillName, java.util.Map<String, Object> payload) {
        if (skillName == null || skillName.isBlank()) {
            return List.of();
        }
        String executionTarget = DecisionCapabilityCatalog.executionTarget(skillName);
        ParamValidator.ValidationResult validation = paramValidator.validate(executionTarget, payload == null ? java.util.Map.of() : payload);
        if (validation.valid() || validation.missingParams().isEmpty()) {
            return List.of();
        }
        return validation.missingParams();
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String capText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "\n...[truncated]";
    }
}

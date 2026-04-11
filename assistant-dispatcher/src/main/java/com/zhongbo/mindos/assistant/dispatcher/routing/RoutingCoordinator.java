package com.zhongbo.mindos.assistant.dispatcher.routing;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class RoutingCoordinator {

    private final SkillEngineFacade skillEngine;

    public RoutingCoordinator(SkillEngineFacade skillEngine) {
        this.skillEngine = skillEngine;
    }

    public List<String> skillSummaries() {
        return skillEngine.listAvailableSkillSummaries();
    }

    public List<SkillDescriptor> skillDescriptors() {
        return skillEngine.listSkillDescriptors();
    }

    public boolean shouldUseMasterOrchestrator(Map<String, Object> profileContext) {
        if (isTruthy(profileContext == null ? null : profileContext.get("multiAgent"))) {
            return true;
        }
        String orchestrationMode = asString(profileContext == null ? null : profileContext.get("orchestrationMode"));
        return "multi-agent".equalsIgnoreCase(orchestrationMode)
                || "master".equalsIgnoreCase(orchestrationMode)
                || "master-orchestrator".equalsIgnoreCase(orchestrationMode);
    }

    public Decision buildMultiAgentDecision(String userInput,
                                            SemanticAnalysisResult semanticAnalysis,
                                            SkillContext context) {
        if (context == null) {
            return null;
        }
        Map<String, Object> params = new java.util.LinkedHashMap<>(context.attributes() == null ? Map.of() : context.attributes());
        if (semanticAnalysis != null) {
            params.putAll(semanticAnalysis.asAttributes());
            if (semanticAnalysis.payload() != null && !semanticAnalysis.payload().isEmpty()) {
                params.putIfAbsent("semanticPayload", semanticAnalysis.payload());
            }
            if (semanticAnalysis.keywords() != null && !semanticAnalysis.keywords().isEmpty()) {
                params.putIfAbsent("semanticKeywords", semanticAnalysis.keywords());
            }
        }
        params.putIfAbsent("input", context.input());
        params.putIfAbsent("multiAgent", true);
        params.putIfAbsent("orchestrationMode", "multi-agent");
        String intent = firstNonBlank(
                semanticAnalysis == null ? null : semanticAnalysis.intent(),
                semanticAnalysis == null ? null : semanticAnalysis.suggestedSkill(),
                context.input()
        );
        String target = firstNonBlank(
                semanticAnalysis == null ? null : semanticAnalysis.suggestedSkill(),
                semanticAnalysis == null ? null : semanticAnalysis.intent(),
                "llm.orchestrate"
        );
        double confidence = semanticAnalysis == null ? 0.75 : Math.max(semanticAnalysis.effectiveConfidence(), 0.75);
        return new Decision(intent, target, params, confidence, false);
    }

    public DispatchPlan preparePlan(String userInput,
                                    SemanticAnalysisResult semanticAnalysis,
                                    SkillContext context,
                                    Map<String, Object> profileContext) {
        boolean multiAgentRequested = shouldUseMasterOrchestrator(profileContext);
        RoutingStage stage = multiAgentRequested ? RoutingStage.MULTI_AGENT : RoutingStage.ROUTING;
        Decision decision = multiAgentRequested ? buildMultiAgentDecision(userInput, semanticAnalysis, context) : null;
        return new DispatchPlan(stage, decision, multiAgentRequested, skillDescriptors(), profileContext);
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

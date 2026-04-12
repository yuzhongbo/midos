package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DefaultDecisionPlanner implements DecisionPlanner {

    private final SkillEngineFacade skillEngine;
    private final DecisionTargetResolver targetResolver;

    public DefaultDecisionPlanner() {
        this(null);
    }

    @Autowired
    public DefaultDecisionPlanner(SkillEngineFacade skillEngine) {
        this.skillEngine = skillEngine;
        this.targetResolver = new DecisionTargetResolver();
    }

    @Override
    public Decision plan(String userInput, String intent, Map<String, Object> params, SkillContext context) {
        Map<String, Object> plannedParams = enrichParams(params, context);
        String target = resolveTarget(intent, plannedParams, context, userInput);
        double confidence = resolveConfidence(target, intent, plannedParams, context);
        String effectiveIntent = resolveIntent(intent, context, target);
        return new Decision(effectiveIntent == null ? "" : effectiveIntent, target, plannedParams, confidence, false);
    }

    private Map<String, Object> enrichParams(Map<String, Object> params, SkillContext context) {
        Map<String, Object> planned = new LinkedHashMap<>(params == null ? Map.of() : params);
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        Object semanticPayload = attributes.get(SemanticAnalysisResult.ATTR_PAYLOAD);
        if (semanticPayload instanceof Map<?, ?> map) {
            map.forEach((key, value) -> planned.putIfAbsent(String.valueOf(key), value));
        }
        if (context != null && hasTarget(context.input())) {
            planned.putIfAbsent("input", context.input());
        }
        String semanticTarget = stringValue(attributes.get(SemanticAnalysisResult.ATTR_SUGGESTED_SKILL));
        if ("todo.create".equals(semanticTarget) && !planned.containsKey("task") && context != null) {
            planned.put("task", context.input());
        }
        if ("eq.coach".equals(semanticTarget) && !planned.containsKey("query") && context != null) {
            planned.put("query", context.input());
        }
        if ("code.generate".equals(semanticTarget) && !planned.containsKey("task") && context != null) {
            planned.put("task", context.input());
        }
        if ("news_search".equals(semanticTarget) && !planned.containsKey("query") && context != null) {
            planned.put("query", context.input());
        }
        if ("file.search".equals(semanticTarget)) {
            planned.putIfAbsent("keyword", context == null ? "" : context.input());
            planned.putIfAbsent("path", "./");
        }
        return Map.copyOf(planned);
    }

    private String resolveTarget(String intent,
                                 Map<String, Object> params,
                                 SkillContext context,
                                 String userInput) {
        if (params != null) {
            String explicitTarget = targetResolver.canonicalize(stringValue(params.get("_target")));
            if (hasTarget(explicitTarget)) {
                return explicitTarget;
            }
            String paramTarget = targetResolver.canonicalize(stringValue(params.get("target")));
            if ((intent == null || intent.isBlank()) && hasTarget(paramTarget)) {
                return paramTarget;
            }
        }
        String intentTarget = targetResolver.canonicalize(intent);
        if (hasTarget(intentTarget)) {
            return intentTarget;
        }
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        String semanticTarget = targetResolver.canonicalize(stringValue(attributes.get(SemanticAnalysisResult.ATTR_SUGGESTED_SKILL)));
        if (hasTarget(semanticTarget)) {
            return semanticTarget;
        }
        String semanticIntentTarget = targetResolver.canonicalize(stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT)));
        if (hasTarget(semanticIntentTarget)) {
            return semanticIntentTarget;
        }
        if (skillEngine != null) {
            String detectionInput = firstNonBlank(
                    context == null ? null : context.input(),
                    stringValue(attributes.get("originalInput")),
                    userInput
            );
            if (hasTarget(detectionInput)) {
                return targetResolver.canonicalize(skillEngine.detectSkillName(detectionInput).orElse(""));
            }
        }
        return "";
    }

    private double resolveConfidence(String target, String intent, Map<String, Object> params, SkillContext context) {
        if (!hasTarget(target)) {
            return 0.0;
        }
        String explicitTarget = params == null ? "" : targetResolver.canonicalize(stringValue(params.get("_target")));
        String paramTarget = params == null ? "" : targetResolver.canonicalize(stringValue(params.get("target")));
        String intentTarget = targetResolver.canonicalize(intent);
        if (target.equals(explicitTarget) || target.equals(paramTarget) || target.equals(intentTarget)) {
            return 1.0;
        }
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        String semanticTarget = targetResolver.canonicalize(stringValue(attributes.get(SemanticAnalysisResult.ATTR_SUGGESTED_SKILL)));
        String semanticIntentTarget = targetResolver.canonicalize(stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT)));
        if (target.equals(semanticTarget) || target.equals(semanticIntentTarget)) {
            Object semanticConfidence = attributes.get(SemanticAnalysisResult.ATTR_CONFIDENCE);
            if (semanticConfidence instanceof Number number) {
                return Math.max(0.0, Math.min(1.0, number.doubleValue()));
            }
        }
        return 0.6;
    }

    private String resolveIntent(String intent, SkillContext context, String target) {
        if (hasTarget(intent)) {
            return intent.trim();
        }
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        String semanticIntent = stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT));
        if (hasTarget(semanticIntent)) {
            return semanticIntent;
        }
        return target;
    }

    private boolean hasTarget(String target) {
        return target != null && !target.isBlank();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.command.TeachingPlanCommandSupport;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultDecisionPlanner implements DecisionPlanner {

    private static final String PLANNER_ROUTE_SOURCE_KEY = "_plannerRouteSource";
    private static final String RULE_FALLBACK_SOURCE = "rule-fallback";

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
        Map<String, Object> plannedParams = mergeBaseParams(params, context);
        ResolvedTarget resolvedTarget = resolveTarget(intent, plannedParams, context, userInput);
        plannedParams = enrichParams(plannedParams, context, resolvedTarget, userInput);
        String target = resolvedTarget.target();
        double confidence = resolveConfidence(resolvedTarget, intent, plannedParams, context);
        String effectiveIntent = resolveIntent(intent, context, target);
        return new Decision(effectiveIntent == null ? "" : effectiveIntent, target, plannedParams, confidence, false);
    }

    private Map<String, Object> mergeBaseParams(Map<String, Object> params, SkillContext context) {
        Map<String, Object> planned = new LinkedHashMap<>(params == null ? Map.of() : params);
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        Object semanticPayload = attributes.get(SemanticAnalysisResult.ATTR_PAYLOAD);
        if (semanticPayload instanceof Map<?, ?> map) {
            map.forEach((key, value) -> planned.putIfAbsent(String.valueOf(key), value));
        }
        if (context != null && hasTarget(context.input())) {
            planned.putIfAbsent("input", context.input());
        }
        return planned;
    }

    private Map<String, Object> enrichParams(Map<String, Object> params,
                                             SkillContext context,
                                             ResolvedTarget resolvedTarget,
                                             String userInput) {
        Map<String, Object> planned = new LinkedHashMap<>(params == null ? Map.of() : params);
        String target = resolvedTarget == null ? "" : resolvedTarget.target();
        String routeSource = resolvedTarget == null ? "" : resolvedTarget.source();
        String effectiveInput = firstNonBlank(context == null ? null : context.input(), userInput);
        if ("todo.create".equals(target) && !planned.containsKey("task") && hasTarget(effectiveInput)) {
            planned.put("task", effectiveInput);
        }
        if ("eq.coach".equals(target) && !planned.containsKey("query") && hasTarget(effectiveInput)) {
            planned.put("query", effectiveInput);
        }
        if ("code.generate".equals(target) && !planned.containsKey("task") && hasTarget(effectiveInput)) {
            planned.put("task", effectiveInput);
        }
        if ("news_search".equals(target) && !planned.containsKey("query") && hasTarget(effectiveInput)) {
            planned.put("query", effectiveInput);
        }
        if ("file.search".equals(target)) {
            planned.putIfAbsent("keyword", effectiveInput);
            planned.putIfAbsent("path", "./");
        }
        if ("echo".equals(target) && !planned.containsKey("text")) {
            String echoText = extractEchoText(userInput);
            if (hasTarget(echoText)) {
                planned.put("text", echoText);
            }
        }
        if ("teaching.plan".equals(target)) {
            TeachingPlanCommandSupport.extractPayload(firstNonBlank(effectiveInput, userInput))
                    .forEach(planned::putIfAbsent);
        }
        if (RULE_FALLBACK_SOURCE.equals(routeSource)) {
            planned.put(PLANNER_ROUTE_SOURCE_KEY, RULE_FALLBACK_SOURCE);
        }
        return Map.copyOf(planned);
    }

    private ResolvedTarget resolveTarget(String intent,
                                         Map<String, Object> params,
                                         SkillContext context,
                                         String userInput) {
        if (params != null) {
            String explicitTarget = targetResolver.canonicalize(stringValue(params.get("_target")));
            if (hasTarget(explicitTarget)) {
                return new ResolvedTarget(explicitTarget, "explicit-target");
            }
            String paramTarget = targetResolver.canonicalize(stringValue(params.get("target")));
            if ((intent == null || intent.isBlank()) && hasTarget(paramTarget)) {
                return new ResolvedTarget(paramTarget, "param-target");
            }
        }
        String intentTarget = targetResolver.canonicalize(intent);
        if (hasTarget(intentTarget)) {
            return new ResolvedTarget(intentTarget, "intent");
        }
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        String semanticTarget = targetResolver.canonicalize(stringValue(attributes.get(SemanticAnalysisResult.ATTR_SUGGESTED_SKILL)));
        if (hasTarget(semanticTarget)) {
            return new ResolvedTarget(semanticTarget, "semantic-suggested-skill");
        }
        String semanticIntentTarget = targetResolver.canonicalize(stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT)));
        if (hasTarget(semanticIntentTarget)) {
            return new ResolvedTarget(semanticIntentTarget, "semantic-intent");
        }
        if (skillEngine != null) {
            String detectionInput = firstNonBlank(
                    context == null ? null : context.input(),
                    stringValue(attributes.get("originalInput")),
                    userInput
            );
            if (hasTarget(detectionInput)) {
                String detectedTarget = targetResolver.canonicalize(skillEngine.detectSkillName(detectionInput).orElse(""));
                if (hasTarget(detectedTarget)) {
                    return new ResolvedTarget(detectedTarget, "skill-detect");
                }
            }
        }
        return resolveRuleFallbackTarget(userInput);
    }

    private double resolveConfidence(ResolvedTarget resolvedTarget, String intent, Map<String, Object> params, SkillContext context) {
        String target = resolvedTarget == null ? "" : resolvedTarget.target();
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
        if (resolvedTarget != null && RULE_FALLBACK_SOURCE.equals(resolvedTarget.source())) {
            return 0.75;
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

    private ResolvedTarget resolveRuleFallbackTarget(String userInput) {
        if (!hasTarget(userInput)) {
            return new ResolvedTarget("", "");
        }
        String normalized = normalize(userInput);
        if (normalized.startsWith("echo ")) {
            return new ResolvedTarget("echo", RULE_FALLBACK_SOURCE);
        }
        if (containsAny(normalized, "time", "clock", "几点", "时间", "what time")) {
            return new ResolvedTarget("time", RULE_FALLBACK_SOURCE);
        }
        if ((normalized.startsWith("code ") || normalized.contains("generate code")) && isCodeGenerationIntent(userInput)) {
            return new ResolvedTarget("code.generate", RULE_FALLBACK_SOURCE);
        }
        if (isTeachingPlanIntent(normalized)) {
            return new ResolvedTarget("teaching.plan", RULE_FALLBACK_SOURCE);
        }
        return new ResolvedTarget("", "");
    }

    private String extractEchoText(String userInput) {
        if (!hasTarget(userInput)) {
            return "";
        }
        String trimmed = userInput.trim();
        if (trimmed.length() <= "echo ".length()) {
            return "";
        }
        if (!trimmed.regionMatches(true, 0, "echo ", 0, "echo ".length())) {
            return "";
        }
        return trimmed.substring("echo ".length()).trim();
    }

    private boolean isCodeGenerationIntent(String input) {
        if (!hasTarget(input)) {
            return false;
        }
        String normalized = normalize(input);
        boolean hasCodeCue = containsAny(normalized,
                "generate code",
                "code ",
                "写代码",
                "生成代码",
                "代码实现",
                "代码示例",
                "写个函数",
                "实现一个",
                "java代码",
                "python代码",
                "sql",
                "接口",
                "api",
                "bug",
                "debug",
                "修复");
        if (!hasCodeCue) {
            return false;
        }
        boolean looksLikeGeneralQuestion = containsAny(normalized,
                "是什么",
                "原理",
                "解释",
                "怎么理解",
                "什么意思",
                "why",
                "what is",
                "explain")
                && !containsAny(normalized, "代码", "函数", "class", "method", "api", "bug", "修复");
        return !looksLikeGeneralQuestion;
    }

    private boolean isTeachingPlanIntent(String normalized) {
        return containsAny(normalized,
                "教学规划",
                "学习计划",
                "复习计划",
                "课程规划",
                "学习路线",
                "study plan",
                "teaching plan");
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record ResolvedTarget(String target, String source) {
    }
}

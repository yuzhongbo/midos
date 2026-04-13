package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionTargetResolver;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FinalPlanner {

    public static final String PLANNER_ROUTE_SOURCE_KEY = "_plannerRouteSource";
    public static final String RULE_FALLBACK_SOURCE = "rule-fallback";

    private final DecisionTargetResolver targetResolver = new DecisionTargetResolver();
    private final DecisionParamAssembler decisionParamAssembler;

    public FinalPlanner() {
        this(new DecisionParamAssembler(new SkillCommandAssembler(new SkillDslParser(new SkillDslValidator()), false)));
    }

    FinalPlanner(DecisionParamAssembler decisionParamAssembler) {
        this.decisionParamAssembler = decisionParamAssembler == null
                ? new DecisionParamAssembler(new SkillCommandAssembler(new SkillDslParser(new SkillDslValidator()), false))
                : decisionParamAssembler;
    }

    public Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        RankedSignal selected = selectSignal(signals);
        if (selected == null) {
            selected = selectSignal(fallbackRuleSignals(safeInput.userInput()));
        }
        String target = selected == null ? "" : selected.target();
        String source = selected == null ? "" : selected.source();
        Map<String, Object> params = decisionParamAssembler.assembleParams(
                target,
                source,
                safeInput.userInput(),
                safeInput.skillContext()
        );
        String intent = resolveIntent(safeInput.skillContext(), selected == null ? "" : selected.intentHint(), target);
        double confidence = selected == null ? 0.0 : selected.confidence();
        return new Decision(intent, target, params, confidence, target.isBlank());
    }

    private RankedSignal selectSignal(List<DecisionSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        Map<String, RankedSignal> aggregated = new LinkedHashMap<>();
        for (DecisionSignal signal : signals) {
            if (signal == null) {
                continue;
            }
            String target = targetResolver.canonicalize(signal.target());
            if (target.isBlank()) {
                continue;
            }
            RankedSignal existing = aggregated.get(target);
            RankedSignal current = new RankedSignal(target, signal.source(), adjustedScore(signal), signal.score(), signal.target());
            if (existing == null) {
                aggregated.put(target, current);
                continue;
            }
            double adjusted = Math.max(existing.adjustedScore(), current.adjustedScore());
            double confidence = Math.max(existing.confidence(), current.confidence());
            boolean currentWins = adjusted == current.adjustedScore() && sourceWeight(current.source()) >= sourceWeight(existing.source());
            String source = currentWins ? current.source() : existing.source();
            String intentHint = currentWins ? current.intentHint() : existing.intentHint();
            aggregated.put(target, new RankedSignal(target, source, adjusted, confidence, intentHint));
        }
        return aggregated.values().stream()
                .sorted((left, right) -> {
                    int byAdjusted = Double.compare(right.adjustedScore(), left.adjustedScore());
                    if (byAdjusted != 0) {
                        return byAdjusted;
                    }
                    int byConfidence = Double.compare(right.confidence(), left.confidence());
                    if (byConfidence != 0) {
                        return byConfidence;
                    }
                    return left.target().compareTo(right.target());
                })
                .findFirst()
                .orElse(null);
    }

    private List<DecisionSignal> fallbackRuleSignals(String userInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.startsWith("echo ")) {
            return List.of(new DecisionSignal("echo", 0.75, RULE_FALLBACK_SOURCE));
        }
        if (containsAny(normalized, "time", "clock", "几点", "时间", "what time")) {
            return List.of(new DecisionSignal("time", 0.75, RULE_FALLBACK_SOURCE));
        }
        if ((normalized.startsWith("code ") || normalized.contains("generate code")) && isCodeGenerationIntent(normalized)) {
            return List.of(new DecisionSignal("code.generate", 0.75, RULE_FALLBACK_SOURCE));
        }
        if (isTeachingPlanIntent(normalized)) {
            return List.of(new DecisionSignal("teaching.plan", 0.75, RULE_FALLBACK_SOURCE));
        }
        return List.of();
    }

    private DecisionSignal canonicalSignal(String rawTarget, double score, String source) {
        String target = targetResolver.canonicalize(rawTarget);
        return target.isBlank() ? null : new DecisionSignal(target, score, source);
    }

    private double adjustedScore(DecisionSignal signal) {
        if (signal == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, signal.score())) + sourceWeight(signal.source());
    }

    private double sourceWeight(String source) {
        return switch (normalize(source)) {
            case "explicit", "profile" -> 0.30;
            case "rule", RULE_FALLBACK_SOURCE -> 0.20;
            case "semantic" -> 0.15;
            case "memory" -> 0.10;
            case "llm" -> 0.05;
            default -> 0.02;
        };
    }

    private String resolveIntent(SkillContext context, String intentHint, String target) {
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        String semanticIntent = stringValue(attributes.get(SemanticAnalysisResult.ATTR_INTENT));
        if (!semanticIntent.isBlank()) {
            return semanticIntent;
        }
        return intentHint == null || intentHint.isBlank() ? target : intentHint;
    }

    private double semanticConfidence(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return 0.82;
        }
        Object raw = attributes.get(SemanticAnalysisResult.ATTR_CONFIDENCE);
        if (raw instanceof Number number) {
            return Math.max(0.0, Math.min(1.0, number.doubleValue()));
        }
        return 0.82;
    }

    private boolean isCodeGenerationIntent(String normalizedInput) {
        return containsAny(normalizedInput,
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
                "修复")
                && !containsAny(normalizedInput,
                "是什么",
                "原理",
                "解释",
                "怎么理解",
                "什么意思",
                "why",
                "what is",
                "explain");
    }

    private boolean isTeachingPlanIntent(String normalizedInput) {
        return containsAny(normalizedInput,
                "教学规划",
                "学习计划",
                "复习计划",
                "课程规划",
                "学习路线",
                "study plan",
                "teaching plan",
                "冲刺路线");
    }

    private boolean containsAny(String normalizedInput, String... terms) {
        if (normalizedInput == null || normalizedInput.isBlank() || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && normalizedInput.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record RankedSignal(String target,
                                String source,
                                double adjustedScore,
                                double confidence,
                                String intentHint) {
    }
}

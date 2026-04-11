package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.SharedMemorySnapshot;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultReflectionAgent implements ReflectionAgent {

    private final MemoryGateway memoryGateway;
    private final String semanticBucket;
    private final String proceduralSkillName;

    public DefaultReflectionAgent() {
        this(null, "autonomous.reflection", "reflection");
    }

    public DefaultReflectionAgent(MemoryGateway memoryGateway) {
        this(memoryGateway, "autonomous.reflection", "reflection");
    }

    @Autowired
    public DefaultReflectionAgent(MemoryGateway memoryGateway,
                                  @Value("${mindos.autonomous.reflection.semantic-bucket:autonomous.reflection}") String semanticBucket,
                                  @Value("${mindos.autonomous.reflection.procedural-skill:reflection}") String proceduralSkillName) {
        this.memoryGateway = memoryGateway;
        this.semanticBucket = normalizeBucket(semanticBucket);
        this.proceduralSkillName = normalizeName(proceduralSkillName, "reflection");
    }

    @Override
    public ReflectionResult reflect(ReflectionRequest request) {
        ReflectionRequest safeRequest = request == null
                ? ReflectionRequest.of("", "", null, null, Map.of(), Map.of())
                : request;
        ReflectionFacts facts = analyse(safeRequest);
        Map<String, Double> scores = scoreDimensions(facts);
        String pattern = selectPattern(scores, facts.success());
        String rootCause = buildRootCause(pattern, facts);
        String improvement = buildImprovement(pattern, facts);
        List<String> signals = buildSignals(facts, scores, pattern);

        ReflectionResult result = new ReflectionResult(
                facts.success(),
                rootCause,
                improvement,
                pattern,
                scores,
                signals,
                false,
                false,
                Instant.now()
        );
        boolean proceduralWritten = writeProcedural(safeRequest, result);
        boolean semanticWritten = writeSemantic(safeRequest, result, facts);
        return result.withMemoryWrites(proceduralWritten, semanticWritten);
    }

    private ReflectionFacts analyse(ReflectionRequest request) {
        ExecutionTraceDto trace = request.trace();
        SkillResult result = request.result();
        RoutingDecisionDto routing = trace == null ? null : trace.routing();
        CritiqueReportDto critique = trace == null ? null : trace.critique();

        List<String> missingParams = findMissingParams(request.params());
        String selectedSkill = firstNonBlank(routing == null ? "" : routing.selectedSkill(), "");
        String actualSkill = result == null ? "" : normalizeText(result.skillName());
        String routeType = routing == null ? "" : normalizeText(routing.route());
        int replanCount = trace == null ? 0 : Math.max(0, trace.replanCount());
        List<String> blockedSteps = new ArrayList<>();
        List<String> failedSteps = new ArrayList<>();
        List<String> traceTokens = new ArrayList<>();

        if (trace != null) {
            traceTokens.add(normalizeText(trace.strategy()));
        }
        if (critique != null) {
            traceTokens.add(normalizeText(critique.reason()));
            traceTokens.add(normalizeText(critique.action()));
        }
        if (routing != null) {
            traceTokens.add(normalizeText(routing.route()));
            traceTokens.add(normalizeText(routing.selectedSkill()));
            traceTokens.addAll(routing.reasons());
            traceTokens.addAll(routing.rejectedReasons());
        }
        if (trace != null && trace.steps() != null) {
            for (PlanStepDto step : trace.steps()) {
                if (step == null) {
                    continue;
                }
                traceTokens.add(normalizeText(step.stepName()));
                traceTokens.add(normalizeText(step.status()));
                traceTokens.add(normalizeText(step.channel()));
                traceTokens.add(normalizeText(step.note()));
                if (isBlocked(step.status(), step.note())) {
                    blockedSteps.add(firstNonBlank(step.stepName(), step.channel(), "step"));
                }
                if (isFailed(step.status(), step.note())) {
                    failedSteps.add(firstNonBlank(step.stepName(), step.channel(), "step"));
                }
            }
        }
        if (result != null) {
            traceTokens.add(normalizeText(result.skillName()));
            traceTokens.add(normalizeText(result.output()));
        }
        if (!request.userInput().isBlank()) {
            traceTokens.add(request.userInput());
        }
        if (!request.context().isEmpty()) {
            traceTokens.addAll(request.context().keySet().stream().map(this::normalizeText).toList());
        }

        boolean hasMemoryContext = hasMemoryContext(request.context());
        String traceText = joinNonBlank(traceTokens);
        return new ReflectionFacts(
                missingParams,
                selectedSkill,
                actualSkill,
                routeType,
                replanCount,
                blockedSteps,
                failedSteps,
                hasMemoryContext,
                traceText,
                request.success()
        );
    }

    private Map<String, Double> scoreDimensions(ReflectionFacts facts) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("parameter", scoreParameter(facts));
        scores.put("skill_selection", scoreSkillSelection(facts));
        scores.put("memory", scoreMemory(facts));
        scores.put("scheduling", scoreScheduling(facts));
        return scores;
    }

    private double scoreParameter(ReflectionFacts facts) {
        double score = 0.0;
        if (!facts.missingParams().isEmpty()) {
            score += 0.55 + Math.min(0.20, facts.missingParams().size() * 0.05);
        }
        if (containsAny(facts.traceText(), "参数", "param", "required", "missing", "缺少", "缺失", "null", "必填")) {
            score += 0.20;
        }
        if (containsAny(facts.traceText(), "补全", "validate", "validator")) {
            score += 0.10;
        }
        if (facts.success()) {
            score *= 0.70;
        }
        return clamp(score);
    }

    private double scoreSkillSelection(ReflectionFacts facts) {
        double score = 0.0;
        if (facts.selectedSkill().isBlank() && !facts.actualSkill().isBlank()) {
            score += 0.25;
        }
        if (!facts.selectedSkill().isBlank()
                && !facts.actualSkill().isBlank()
                && !facts.selectedSkill().equalsIgnoreCase(facts.actualSkill())) {
            score += 0.45;
        }
        if (containsAny(facts.traceText(), "fallback", "skill", "route", "tool", "model", "candidate", "选择", "路由")) {
            score += 0.20;
        }
        if (containsAny(facts.routeType(), "MCP", "REMOTE", "LOCAL")) {
            score += 0.05;
        }
        if (facts.success()) {
            score *= 0.80;
        }
        return clamp(score);
    }

    private double scoreMemory(ReflectionFacts facts) {
        double score = 0.0;
        if (!facts.hasMemoryContext() && !facts.success()) {
            score += 0.35;
        }
        if (!facts.hasMemoryContext()
                && containsAny(facts.traceText(), "记忆", "memory", "上下文", "persona", "semantic", "procedural", "knowledge", "history")) {
            score += 0.35;
        }
        if (facts.contextAbsentOrSparse() && !facts.success()) {
            score += 0.15;
        }
        if (facts.success()) {
            score *= 0.75;
        }
        return clamp(score);
    }

    private double scoreScheduling(ReflectionFacts facts) {
        double score = 0.0;
        if (facts.replanCount() > 0) {
            score += 0.25 + Math.min(0.30, facts.replanCount() * 0.08);
        }
        if (!facts.blockedSteps().isEmpty()) {
            score += 0.20 + Math.min(0.15, facts.blockedSteps().size() * 0.05);
        }
        if (!facts.failedSteps().isEmpty()) {
            score += 0.10 + Math.min(0.15, facts.failedSteps().size() * 0.04);
        }
        if (containsAny(facts.traceText(), "replan", "retry", "blocked", "schedule", "dag", "dependency", "依赖", "阻塞", "重试")) {
            score += 0.15;
        }
        if (facts.success()) {
            score *= 0.85;
        }
        return clamp(score);
    }

    private String selectPattern(Map<String, Double> scores, boolean success) {
        if (scores == null || scores.isEmpty()) {
            return success ? "stable" : "unknown";
        }
        List<Map.Entry<String, Double>> ranking = scores.entrySet().stream()
                .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                .toList();
        if (ranking.isEmpty()) {
            return success ? "stable" : "unknown";
        }
        Map.Entry<String, Double> top = ranking.get(0);
        double topScore = top.getValue() == null ? 0.0 : top.getValue();
        double secondScore = ranking.size() > 1 && ranking.get(1).getValue() != null ? ranking.get(1).getValue() : 0.0;
        if (success && topScore < 0.30) {
            return "stable";
        }
        if (!success && topScore < 0.30) {
            return "unknown";
        }
        if (topScore >= 0.40 && secondScore >= 0.40 && topScore - secondScore <= 0.18) {
            return "mixed";
        }
        return toPatternName(top.getKey());
    }

    private String buildRootCause(String pattern, ReflectionFacts facts) {
        return switch (pattern) {
            case "missing_param" -> buildParameterRootCause(facts);
            case "skill_selection" -> buildSkillSelectionRootCause(facts);
            case "memory_gap" -> buildMemoryRootCause(facts);
            case "scheduling" -> buildSchedulingRootCause(facts);
            case "mixed" -> buildMixedRootCause(facts);
            case "stable" -> "执行成功，未发现明显单点问题";
            default -> "当前 trace 无法定位单点根因";
        };
    }

    private String buildImprovement(String pattern, ReflectionFacts facts) {
        return switch (pattern) {
            case "missing_param" -> buildParameterImprovement(facts);
            case "skill_selection" -> buildSkillSelectionImprovement(facts);
            case "memory_gap" -> buildMemoryImprovement();
            case "scheduling" -> buildSchedulingImprovement();
            case "mixed" -> "先补参，再重新路由，并补齐 memory/context 后重跑";
            case "stable" -> "将该路径固化为 procedural memory，后续优先复用";
            default -> "补充更完整的 trace、params 和 context 后再次复盘";
        };
    }

    private String buildParameterRootCause(ReflectionFacts facts) {
        if (facts.missingParams().isEmpty()) {
            return "参数问题：trace 中出现参数/必填项异常";
        }
        return "参数缺失导致调用失败：" + String.join("、", facts.missingParams()) + " 未补全";
    }

    private String buildParameterImprovement(ReflectionFacts facts) {
        if (facts.missingParams().isEmpty()) {
            return "在执行前增加 ParamValidator，先补全必填参数，再进入 skill 调用";
        }
        return "在执行前增加 ParamValidator 补全 " + String.join("、", facts.missingParams()) + "，缺失时先澄清";
    }

    private String buildSkillSelectionRootCause(ReflectionFacts facts) {
        if (!facts.selectedSkill().isBlank() && !facts.actualSkill().isBlank()) {
            return "技能选择偏差：路由命中 " + facts.selectedSkill() + "，但实际执行为 " + facts.actualSkill();
        }
        if (!facts.selectedSkill().isBlank()) {
            return "技能选择不稳定：当前路由命中 " + facts.selectedSkill() + "，但执行结果不匹配";
        }
        return "技能选择失败：未命中合适的 skill";
    }

    private String buildSkillSelectionImprovement(ReflectionFacts facts) {
        if (!facts.selectedSkill().isBlank() && !facts.actualSkill().isBlank()) {
            return "优化 Skill/Router 选择逻辑，先按目标和参数约束筛选 skill，再执行 " + facts.selectedSkill();
        }
        return "优化 Skill/Router 选择逻辑，先按目标和参数约束筛选 skill，再执行";
    }

    private String buildMemoryRootCause(ReflectionFacts facts) {
        if (facts.hasMemoryContext()) {
            return "记忆上下文可用，但 trace 仍提示 memory 补全不足";
        }
        return "记忆上下文不足，无法补全关键约束";
    }

    private String buildMemoryImprovement() {
        return "先读取 Persona / Semantic / Procedural Memory，再把缺失约束写回上下文";
    }

    private String buildSchedulingRootCause(ReflectionFacts facts) {
        if (!facts.blockedSteps().isEmpty()) {
            return "调度顺序或依赖处理不合理，阻塞在 " + joinLimited(facts.blockedSteps(), 3);
        }
        if (facts.replanCount() > 0) {
            return "调度出现多次重排，replanCount=" + facts.replanCount();
        }
        return "调度链路存在重试或等待问题";
    }

    private String buildSchedulingImprovement() {
        return "优化 DAG 依赖和重试策略，优先处理阻塞节点，减少无效重排";
    }

    private String buildMixedRootCause(ReflectionFacts facts) {
        List<String> causes = new ArrayList<>();
        if (!facts.missingParams().isEmpty()) {
            causes.add("参数缺失");
        }
        if (!facts.selectedSkill().isBlank() && !facts.actualSkill().isBlank() && !facts.selectedSkill().equalsIgnoreCase(facts.actualSkill())) {
            causes.add("路由偏差");
        }
        if (!facts.hasMemoryContext()) {
            causes.add("记忆不足");
        }
        if (facts.replanCount() > 0 || !facts.blockedSteps().isEmpty()) {
            causes.add("调度异常");
        }
        if (causes.isEmpty()) {
            return "多个维度同时异常";
        }
        return "多个维度同时异常：" + String.join(" + ", causes);
    }

    private List<String> buildSignals(ReflectionFacts facts, Map<String, Double> scores, String pattern) {
        List<String> signals = new ArrayList<>();
        if (!facts.missingParams().isEmpty()) {
            signals.add("missingParams=" + String.join("|", facts.missingParams()));
        }
        if (!facts.selectedSkill().isBlank()) {
            signals.add("selectedSkill=" + facts.selectedSkill());
        }
        if (!facts.actualSkill().isBlank()) {
            signals.add("actualSkill=" + facts.actualSkill());
        }
        if (!facts.routeType().isBlank()) {
            signals.add("route=" + facts.routeType());
        }
        if (facts.replanCount() > 0) {
            signals.add("replanCount=" + facts.replanCount());
        }
        if (!facts.blockedSteps().isEmpty()) {
            signals.add("blockedSteps=" + joinLimited(facts.blockedSteps(), 3));
        }
        if (!facts.failedSteps().isEmpty()) {
            signals.add("failedSteps=" + joinLimited(facts.failedSteps(), 3));
        }
        signals.add("memoryContext=" + facts.hasMemoryContext());
        signals.add("pattern=" + pattern);
        signals.add("scores=" + formatScores(scores));
        if (!facts.traceText().isBlank()) {
            signals.add("traceHint=" + truncate(facts.traceText(), 180));
        }
        return List.copyOf(signals);
    }

    private boolean writeProcedural(ReflectionRequest request, ReflectionResult result) {
        if (memoryGateway == null || request.userId().isBlank()) {
            return false;
        }
        memoryGateway.writeProcedural(
                request.userId(),
                ProceduralMemoryEntry.of(proceduralSkillName, result.summary(), result.success())
        );
        return true;
    }

    private boolean writeSemantic(ReflectionRequest request, ReflectionResult result, ReflectionFacts facts) {
        if (memoryGateway == null || request.userId().isBlank()) {
            return false;
        }
        String semanticText = buildSemanticText(result, facts);
        memoryGateway.writeSemantic(request.userId(), semanticText, buildEmbedding(result, facts), semanticBucket);
        return true;
    }

    private String buildSemanticText(ReflectionResult result, ReflectionFacts facts) {
        return "reflection | pattern=" + result.pattern()
                + " | success=" + result.success()
                + " | rootCause=" + result.rootCause()
                + " | improvement=" + result.improvement()
                + " | signals=" + String.join("; ", result.signals())
                + " | traceHint=" + truncate(facts.traceText(), 160);
    }

    private List<Double> buildEmbedding(ReflectionResult result, ReflectionFacts facts) {
        return List.of(
                scoreOrZero(result.dimensionScores(), "parameter"),
                scoreOrZero(result.dimensionScores(), "skill_selection"),
                scoreOrZero(result.dimensionScores(), "memory"),
                scoreOrZero(result.dimensionScores(), "scheduling"),
                result.success() ? 1.0 : 0.0,
                Math.min(1.0, facts.replanCount() / 5.0),
                Math.min(1.0, facts.missingParams().size() / 5.0)
        );
    }

    private double scoreOrZero(Map<String, Double> scores, String key) {
        if (scores == null || scores.isEmpty() || key == null) {
            return 0.0;
        }
        Double value = scores.get(key);
        return value == null ? 0.0 : value;
    }

    private boolean hasMemoryContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String key = normalizeText(entry.getKey()).toLowerCase(Locale.ROOT);
            if (containsAny(key, "memory", "semantic", "procedural", "persona", "knowledge", "history", "inferred")) {
                return true;
            }
            Object value = entry.getValue();
            if (value instanceof SharedMemorySnapshot snapshot && !snapshot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<String> findMissingParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            if (key.isBlank()) {
                continue;
            }
            if (isMissingValue(entry.getValue())) {
                missing.add(key);
            }
        }
        return missing.isEmpty() ? List.of() : List.copyOf(missing);
    }

    private boolean isMissingValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return text.toString().trim().isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Iterable<?> iterable) {
            return !iterable.iterator().hasNext();
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return Array.getLength(value) == 0;
        }
        return false;
    }

    private boolean isBlocked(String status, String note) {
        return containsAny(normalizeText(status).toLowerCase(Locale.ROOT), "blocked", "skip", "wait")
                || containsAny(normalizeText(note), "阻塞", "等待", "blocked", "skip");
    }

    private boolean isFailed(String status, String note) {
        String normalizedStatus = normalizeText(status).toLowerCase(Locale.ROOT);
        return containsAny(normalizedStatus, "fail", "error", "miss", "reject")
                || containsAny(normalizeText(note), "失败", "错误", "异常", "fail", "error", "reject");
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && normalizedText.contains(needle.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String formatScores(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            parts.add(entry.getKey() + "=" + round(entry.getValue()));
        }
        return String.join(",", parts);
    }

    private String joinNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String text = value.trim();
            if (!text.isBlank()) {
                normalized.add(text);
            }
        }
        return String.join(" | ", normalized);
    }

    private String joinLimited(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        int effectiveLimit = Math.max(1, limit);
        List<String> trimmed = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .limit(effectiveLimit)
                .toList();
        return String.join("|", trimmed);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
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

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeName(String value, String fallback) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String toPatternName(String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return "unknown";
        }
        return switch (dimensionKey) {
            case "parameter" -> "missing_param";
            case "skill_selection" -> "skill_selection";
            case "memory" -> "memory_gap";
            case "scheduling" -> "scheduling";
            default -> dimensionKey;
        };
    }

    private String normalizeBucket(String value) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? "autonomous.reflection" : normalized;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record ReflectionFacts(List<String> missingParams,
                                   String selectedSkill,
                                   String actualSkill,
                                   String routeType,
                                   int replanCount,
                                   List<String> blockedSteps,
                                   List<String> failedSteps,
                                   boolean hasMemoryContext,
                                   String traceText,
                                   boolean success) {

        private ReflectionFacts {
            missingParams = missingParams == null ? List.of() : List.copyOf(missingParams);
            selectedSkill = selectedSkill == null ? "" : selectedSkill.trim();
            actualSkill = actualSkill == null ? "" : actualSkill.trim();
            routeType = routeType == null ? "" : routeType.trim();
            replanCount = Math.max(0, replanCount);
            blockedSteps = blockedSteps == null ? List.of() : List.copyOf(blockedSteps);
            failedSteps = failedSteps == null ? List.of() : List.copyOf(failedSteps);
            traceText = traceText == null ? "" : traceText.trim();
        }

        private boolean contextAbsentOrSparse() {
            return !hasMemoryContext;
        }
    }
}

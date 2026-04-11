package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

final class SemanticRoutingSupport {

    private static final Logger LOGGER = Logger.getLogger(SemanticRoutingSupport.class.getName());
    private static final double DEFAULT_CLARIFY_CONFIDENCE_THRESHOLD = 0.70;
    private static final double SEMANTIC_SUMMARY_MIN_CONFIDENCE = 0.60;

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final ParamValidator paramValidator;
    private final Predicate<String> knownSkillNameChecker;
    private final Function<String, String> memoryBucketResolver;
    private final double semanticAnalysisRouteMinConfidence;
    private final double semanticAnalysisClarifyMinConfidence;
    private final boolean preferSuggestedSkillEnabled;
    private final double preferSuggestedSkillMinConfidence;

    SemanticRoutingSupport(DispatcherMemoryFacade dispatcherMemoryFacade,
                           BehaviorRoutingSupport behaviorRoutingSupport,
                           ParamValidator paramValidator,
                           Predicate<String> knownSkillNameChecker,
                           Function<String, String> memoryBucketResolver,
                           double semanticAnalysisRouteMinConfidence,
                           double semanticAnalysisClarifyMinConfidence,
                           boolean preferSuggestedSkillEnabled,
                           double preferSuggestedSkillMinConfidence) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.paramValidator = paramValidator;
        this.knownSkillNameChecker = knownSkillNameChecker;
        this.memoryBucketResolver = memoryBucketResolver;
        this.semanticAnalysisRouteMinConfidence = semanticAnalysisRouteMinConfidence;
        this.semanticAnalysisClarifyMinConfidence = semanticAnalysisClarifyMinConfidence;
        this.preferSuggestedSkillEnabled = preferSuggestedSkillEnabled;
        this.preferSuggestedSkillMinConfidence = preferSuggestedSkillMinConfidence;
    }

    Optional<SkillDsl> toSemanticSkillDsl(SemanticRoutingPlan semanticPlan) {
        if (semanticPlan == null || !semanticPlan.routable() || semanticPlan.skillName().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>(
                semanticPlan.effectivePayload() == null ? Map.of() : semanticPlan.effectivePayload()
        );
        return payload.isEmpty()
                ? Optional.of(SkillDsl.of(semanticPlan.skillName()))
                : Optional.of(new SkillDsl(semanticPlan.skillName(), payload));
    }

    void maybeStoreSemanticSummary(String userId,
                                   String userInput,
                                   SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null || semanticAnalysis.summary() == null || semanticAnalysis.summary().isBlank()) {
            return;
        }
        if (resolveSemanticAnalysisConfidence(semanticAnalysis) < SEMANTIC_SUMMARY_MIN_CONFIDENCE) {
            return;
        }
        String summary = capText(semanticAnalysis.summary(), 220);
        if (summary.isBlank()) {
            return;
        }
        String paramsDigest = summarizeSemanticParams(semanticAnalysis.payload());
        String memoryText = "semantic-summary intent="
                + capText(semanticAnalysis.intent() == null ? "" : semanticAnalysis.intent(), 48)
                + ", skill="
                + capText(semanticAnalysis.suggestedSkill() == null ? "" : semanticAnalysis.suggestedSkill(), 48)
                + ", summary="
                + summary
                + (paramsDigest.isBlank() ? "" : ", params=" + paramsDigest);
        List<Double> embedding = List.of(
                (double) memoryText.length(),
                Math.abs(memoryText.hashCode() % 1000) / 1000.0
        );
        dispatcherMemoryFacade.writeSemantic(userId, memoryText, embedding, resolveMemoryBucket(userInput));
    }

    boolean shouldAskSemanticClarification(SemanticAnalysisResult semanticAnalysis,
                                           String input,
                                           SemanticRoutingPlan semanticPlan) {
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

    SemanticRoutingPlan buildSemanticRoutingPlan(String userId,
                                                 SemanticAnalysisResult semanticAnalysis,
                                                 String originalInput) {
        String skillName = resolveSemanticRoutingSkill(semanticAnalysis);
        if (skillName.isBlank()) {
            return SemanticRoutingPlan.empty();
        }
        Map<String, Object> effectivePayload = buildEffectiveSemanticPayload(userId, semanticAnalysis, originalInput, skillName);
        double confidence = resolveSemanticRouteConfidence(semanticAnalysis, skillName);
        boolean routable = confidence >= semanticAnalysisRouteMinConfidence && isSemanticDirectSkillCandidate(skillName);

        if (!routable && preferSuggestedSkillEnabled && semanticAnalysis != null) {
            String suggested = normalizeOptional(semanticAnalysis.suggestedSkill());
            if (!suggested.isBlank() && isKnownSkillName(suggested)) {
                double suggestedConf = resolveSemanticRouteConfidence(semanticAnalysis, suggested);
                if (suggestedConf >= preferSuggestedSkillMinConfidence) {
                    skillName = suggested;
                    effectivePayload = buildEffectiveSemanticPayload(userId, semanticAnalysis, originalInput, skillName);
                    confidence = suggestedConf;
                    routable = true;
                    LOGGER.fine("Dispatcher: accepting suggestedSkill override=" + skillName + ", conf=" + confidence);
                }
            }
        }

        return new SemanticRoutingPlan(skillName, effectivePayload, confidence, routable);
    }

    String buildSemanticClarifyReply(SemanticAnalysisResult semanticAnalysis,
                                     SemanticRoutingPlan semanticPlan) {
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

    private Map<String, Object> buildEffectiveSemanticPayload(String userId,
                                                              SemanticAnalysisResult semanticAnalysis,
                                                              String originalInput,
                                                              String targetSkill) {
        if (semanticAnalysis == null || targetSkill == null || targetSkill.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>(semanticAnalysis.payload());
        switch (targetSkill) {
            case "code.generate" -> payload.putIfAbsent("task", semanticAnalysis.routingInput(originalInput));
            case "todo.create" -> payload.putIfAbsent("task", semanticAnalysis.routingInput(originalInput));
            case "eq.coach" -> payload.putIfAbsent("query", semanticAnalysis.routingInput(originalInput));
            case "teaching.plan" -> payload.putAll(behaviorRoutingSupport.extractTeachingPlanPayload(originalInput));
            case "file.search" -> {
                payload.putIfAbsent("path", "./");
                payload.putIfAbsent("keyword", semanticAnalysis.routingInput(originalInput));
            }
            default -> {
            }
        }
        if (isMcpSearchSkill(targetSkill)) {
            payload.putIfAbsent("query", semanticAnalysis.routingInput(originalInput));
        }
        completeSemanticPayloadFromMemory(userId, targetSkill, semanticAnalysis, payload, originalInput);
        return payload;
    }

    private void completeSemanticPayloadFromMemory(String userId,
                                                   String targetSkill,
                                                   SemanticAnalysisResult semanticAnalysis,
                                                   Map<String, Object> payload,
                                                   String originalInput) {
        if (userId == null || userId.isBlank() || payload == null) {
            return;
        }
        String skill = targetSkill == null || targetSkill.isBlank()
                ? (semanticAnalysis == null ? "" : semanticAnalysis.suggestedSkill())
                : targetSkill;
        if (skill == null || skill.isBlank()) {
            return;
        }
        String summary = semanticAnalysis.summary() == null ? "" : semanticAnalysis.summary().trim();
        String routingInput = semanticAnalysis.routingInput(originalInput);
        String memoryQuery = summary.isBlank() ? routingInput : summary;
        List<SemanticMemoryEntry> related = dispatcherMemoryFacade.searchKnowledge(
                userId,
                memoryQuery,
                3,
                resolveMemoryBucket(originalInput)
        );
        String memoryHint = related.isEmpty() ? "" : related.get(0).text();

        if ("todo.create".equals(skill) && isBlankValue(payload.get("task"))) {
            String fallbackTask = !summary.isBlank() ? summary : (!memoryHint.isBlank() ? memoryHint : routingInput);
            if (!fallbackTask.isBlank()) {
                payload.put("task", capText(fallbackTask, 140));
            }
        }
        if ("eq.coach".equals(skill) && isBlankValue(payload.get("query"))) {
            String fallbackQuery = !summary.isBlank() ? summary : (!memoryHint.isBlank() ? memoryHint : routingInput);
            if (!fallbackQuery.isBlank()) {
                payload.put("query", capText(fallbackQuery, 180));
            }
        }
        if ("file.search".equals(skill)) {
            if (isBlankValue(payload.get("path"))) {
                payload.put("path", "./");
            }
            if (isBlankValue(payload.get("keyword"))) {
                String fallbackKeyword = !summary.isBlank() ? summary : routingInput;
                payload.put("keyword", capText(fallbackKeyword, 120));
            }
        }
        if (isMcpSearchSkill(skill) && isBlankValue(payload.get("query"))) {
            String fallbackQuery = !summary.isBlank() ? summary : (!memoryHint.isBlank() ? memoryHint : routingInput);
            if (!fallbackQuery.isBlank()) {
                payload.put("query", capText(fallbackQuery, 120));
            }
        }
        List<String> filledKeys = new ArrayList<>();
        Set<String> beforeKeys = payload.isEmpty() ? Set.of() : new LinkedHashSet<>(payload.keySet());
        behaviorRoutingSupport.applyBehaviorLearnedDefaults(userId, skill, payload);
        for (String key : payload.keySet()) {
            if (!beforeKeys.contains(key)) {
                filledKeys.add(key);
            }
        }
        if (!filledKeys.isEmpty()) {
            LOGGER.info(() -> "semantic.payload.completed userId=" + userId + ", skill=" + skill + ", filled=" + filledKeys + ", memoryHintPresent=" + !memoryHint.isBlank());
        }
    }

    private String summarizeSemanticParams(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        List<String> keys = List.of("task", "query", "keyword", "topic", "goal", "dueDate");
        List<String> pairs = new ArrayList<>();
        for (String key : keys) {
            Object value = payload.get(key);
            if (isBlankValue(value)) {
                continue;
            }
            pairs.add(key + "=" + capText(String.valueOf(value).trim(), 40));
            if (pairs.size() >= 3) {
                break;
            }
        }
        return String.join(";", pairs);
    }

    private List<String> missingRequiredParamsForSkill(String skillName, Map<String, Object> payload) {
        if (skillName == null || skillName.isBlank()) {
            return List.of();
        }
        ParamValidator.ValidationResult validation = paramValidator.validate(skillName, payload == null ? Map.of() : payload);
        if (validation.valid() || validation.missingParams().isEmpty()) {
            return List.of();
        }
        return validation.missingParams();
    }

    private String resolveSemanticRoutingSkill(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return "";
        }
        List<String> candidates = new ArrayList<>();
        String suggestedSkill = normalizeOptional(semanticAnalysis.suggestedSkill());
        if (!suggestedSkill.isBlank()) {
            candidates.add(suggestedSkill);
        }
        semanticAnalysis.candidateIntents().stream()
                .sorted((left, right) -> Double.compare(right.confidence(), left.confidence()))
                .map(SemanticAnalysisResult.CandidateIntent::intent)
                .map(this::normalizeOptional)
                .filter(candidate -> !candidate.isBlank() && !candidates.contains(candidate))
                .forEach(candidates::add);
        return candidates.stream()
                .filter(this::isSemanticDirectSkillCandidate)
                .max(java.util.Comparator.comparingDouble(candidate -> resolveSemanticRouteConfidence(semanticAnalysis, candidate)))
                .orElse("");
    }

    private boolean isSemanticDirectSkillCandidate(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if ("semantic.analyze".equals(skillName)) {
            return false;
        }
        if ("code.generate".equals(skillName)) {
            return false;
        }
        return isKnownSkillName(skillName);
    }

    private double resolveSemanticRouteConfidence(SemanticAnalysisResult semanticAnalysis, String skillName) {
        if (semanticAnalysis == null || skillName == null || skillName.isBlank()) {
            return 0.0;
        }
        return Math.max(semanticAnalysis.confidence(), semanticAnalysis.confidenceForSkill(skillName));
    }

    private double resolveSemanticAnalysisConfidence(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return 0.0;
        }
        String bestSkill = resolveSemanticRoutingSkill(semanticAnalysis);
        if (bestSkill.isBlank()) {
            return semanticAnalysis.confidence();
        }
        return resolveSemanticRouteConfidence(semanticAnalysis, bestSkill);
    }

    private boolean isMcpSearchSkill(String skillName) {
        String normalized = normalize(skillName);
        return normalized.startsWith("mcp.")
                && (normalized.contains("search") || normalized.endsWith("query"));
    }

    private boolean isKnownSkillName(String skillName) {
        return knownSkillNameChecker != null && knownSkillNameChecker.test(skillName);
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank();
    }

    private String resolveMemoryBucket(String input) {
        if (memoryBucketResolver == null) {
            return "general";
        }
        String resolved = memoryBucketResolver.apply(input);
        return resolved == null || resolved.isBlank() ? "general" : resolved;
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

    record SemanticRoutingPlan(String skillName,
                               Map<String, Object> effectivePayload,
                               double confidence,
                               boolean routable) {
        static SemanticRoutingPlan empty() {
            return new SemanticRoutingPlan("", Map.of(), 0.0, false);
        }
    }
}

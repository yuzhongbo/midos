package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;

final class SemanticSkillResolver {

    private static final Logger LOGGER = Logger.getLogger(SemanticSkillResolver.class.getName());

    private final Predicate<String> knownSkillNameChecker;
    private final double semanticAnalysisRouteMinConfidence;
    private final boolean preferSuggestedSkillEnabled;
    private final double preferSuggestedSkillMinConfidence;
    private final SemanticPayloadCompleter semanticPayloadCompleter;

    SemanticSkillResolver(Predicate<String> knownSkillNameChecker,
                          double semanticAnalysisRouteMinConfidence,
                          boolean preferSuggestedSkillEnabled,
                          double preferSuggestedSkillMinConfidence,
                          SemanticPayloadCompleter semanticPayloadCompleter) {
        this.knownSkillNameChecker = knownSkillNameChecker;
        this.semanticAnalysisRouteMinConfidence = semanticAnalysisRouteMinConfidence;
        this.preferSuggestedSkillEnabled = preferSuggestedSkillEnabled;
        this.preferSuggestedSkillMinConfidence = preferSuggestedSkillMinConfidence;
        this.semanticPayloadCompleter = semanticPayloadCompleter;
    }

    Optional<SkillDsl> toSemanticSkillDsl(SemanticRoutingSupport.SemanticRoutingPlan semanticPlan) {
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

    SemanticRoutingSupport.SemanticRoutingPlan buildSemanticRoutingPlan(String userId,
                                                                        SemanticAnalysisResult semanticAnalysis,
                                                                        String originalInput) {
        String skillName = resolveSemanticRoutingSkill(semanticAnalysis);
        if (skillName.isBlank()) {
            return SemanticRoutingSupport.SemanticRoutingPlan.empty();
        }
        Map<String, Object> effectivePayload = semanticPayloadCompleter.buildEffectiveSemanticPayload(
                userId,
                semanticAnalysis,
                originalInput,
                skillName
        );
        double confidence = resolveSemanticRouteConfidence(semanticAnalysis, skillName);
        boolean routable = confidence >= semanticAnalysisRouteMinConfidence && isSemanticDirectSkillCandidate(skillName);

        if (!routable && preferSuggestedSkillEnabled && semanticAnalysis != null) {
            String suggested = normalizeOptional(semanticAnalysis.suggestedSkill());
            if (!suggested.isBlank() && isKnownSkillName(suggested)) {
                double suggestedConf = resolveSemanticRouteConfidence(semanticAnalysis, suggested);
                if (suggestedConf >= preferSuggestedSkillMinConfidence) {
                    skillName = suggested;
                    effectivePayload = semanticPayloadCompleter.buildEffectiveSemanticPayload(
                            userId,
                            semanticAnalysis,
                            originalInput,
                            skillName
                    );
                    confidence = suggestedConf;
                    routable = true;
                    LOGGER.fine("Dispatcher: accepting suggestedSkill override=" + skillName + ", conf=" + confidence);
                }
            }
        }

        return new SemanticRoutingSupport.SemanticRoutingPlan(skillName, effectivePayload, confidence, routable);
    }

    double resolveSemanticAnalysisConfidence(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return 0.0;
        }
        String bestSkill = resolveSemanticRoutingSkill(semanticAnalysis);
        if (bestSkill.isBlank()) {
            return semanticAnalysis.confidence();
        }
        return resolveSemanticRouteConfidence(semanticAnalysis, bestSkill);
    }

    private String resolveSemanticRoutingSkill(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return "";
        }
        java.util.List<String> candidates = new ArrayList<>();
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
                .max(Comparator.comparingDouble(candidate -> resolveSemanticRouteConfidence(semanticAnalysis, candidate)))
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

    private boolean isKnownSkillName(String skillName) {
        return knownSkillNameChecker != null && knownSkillNameChecker.test(skillName);
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }
}

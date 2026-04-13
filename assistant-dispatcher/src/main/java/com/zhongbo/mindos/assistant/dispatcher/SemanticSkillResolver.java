package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class SemanticSkillResolver {

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

    List<DecisionSignal> recommend(RecommendationInput input) {
        SemanticAnalysisResult semanticAnalysis = input == null ? null : input.semanticAnalysis();
        if (semanticAnalysis == null) {
            return List.of();
        }
        Map<String, DecisionSignal> recommendations = new LinkedHashMap<>();
        String suggestedSkill = normalizeOptional(semanticAnalysis.suggestedSkill());
        if (!suggestedSkill.isBlank() && isSemanticDirectSkillCandidate(suggestedSkill)) {
            double confidence = resolveSemanticRouteConfidence(semanticAnalysis, suggestedSkill);
            if (!preferSuggestedSkillEnabled || confidence >= preferSuggestedSkillMinConfidence) {
                recommendations.put(suggestedSkill, new DecisionSignal(suggestedSkill, confidence, "semantic"));
            }
        }
        semanticAnalysis.candidateIntents().stream()
                .sorted((left, right) -> Double.compare(right.confidence(), left.confidence()))
                .map(SemanticAnalysisResult.CandidateIntent::intent)
                .map(this::normalizeOptional)
                .filter(this::isSemanticDirectSkillCandidate)
                .forEach(skill -> recommendations.putIfAbsent(
                        skill,
                        new DecisionSignal(skill, resolveSemanticRouteConfidence(semanticAnalysis, skill), "semantic")
                ));
        return recommendations.values().stream()
                .sorted(Comparator.comparingDouble(DecisionSignal::score).reversed()
                        .thenComparing(candidate -> suggestedSkill.equals(candidate.target()) ? 0 : 1)
                        .thenComparing(DecisionSignal::target))
                .toList();
    }

    double resolveSemanticAnalysisConfidence(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return 0.0;
        }
        return recommend(new RecommendationInput("", semanticAnalysis, "")).stream()
                .mapToDouble(DecisionSignal::score)
                .max()
                .orElse(semanticAnalysis.confidence());
    }

    boolean isRoutable(DecisionSignal candidate, RecommendationInput input) {
        if (candidate == null || input == null || input.semanticAnalysis() == null) {
            return false;
        }
        if (!isSemanticDirectSkillCandidate(candidate.target())) {
            return false;
        }
        String suggestedSkill = normalizeOptional(input.semanticAnalysis().suggestedSkill());
        if (preferSuggestedSkillEnabled
                && suggestedSkill.equals(candidate.target())
                && candidate.score() >= preferSuggestedSkillMinConfidence) {
            return true;
        }
        return candidate.score() >= semanticAnalysisRouteMinConfidence;
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

    record RecommendationInput(String userId,
                               SemanticAnalysisResult semanticAnalysis,
                               String originalInput) {
    }
}

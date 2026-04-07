package com.zhongbo.mindos.assistant.skill.semantic;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticAnalysisResult(String source,
                                     String intent,
                                     String rewrittenInput,
                                     String suggestedSkill,
                                     Map<String, Object> payload,
                                     List<String> keywords,
                                     String summary,
                                     double confidence,
                                     List<CandidateIntent> candidateIntents) {

    public static final String ATTR_ANALYSIS_SOURCE = "semanticAnalysisSource";
    public static final String ATTR_INTENT = "semanticIntent";
    public static final String ATTR_REWRITTEN_INPUT = "semanticRewrittenInput";
    public static final String ATTR_SUGGESTED_SKILL = "semanticSuggestedSkill";
    public static final String ATTR_CONFIDENCE = "semanticConfidence";
    public static final String ATTR_SUMMARY = "semanticSummary";
    public static final String ATTR_PAYLOAD = "semanticPayload";
    public static final String ATTR_KEYWORDS = "semanticKeywords";
    public static final String ATTR_CANDIDATE_INTENTS = "semanticCandidateIntents";

    private static final SemanticAnalysisResult EMPTY =
            new SemanticAnalysisResult("disabled", "", "", "", Map.of(), List.of(), "", 0.0, List.of());

    public SemanticAnalysisResult(String source,
                                  String intent,
                                  String rewrittenInput,
                                  String suggestedSkill,
                                  Map<String, Object> payload,
                                  List<String> keywords,
                                  double confidence) {
        this(source, intent, rewrittenInput, suggestedSkill, payload, keywords, "", confidence, List.of());
    }

    public SemanticAnalysisResult(String source,
                                  String intent,
                                  String rewrittenInput,
                                  String suggestedSkill,
                                  Map<String, Object> payload,
                                  List<String> keywords,
                                  String summary,
                                  double confidence) {
        this(source, intent, rewrittenInput, suggestedSkill, payload, keywords, summary, confidence, List.of());
    }

    public SemanticAnalysisResult {
        payload = toImmutablePayload(payload);
        keywords = toImmutableKeywords(keywords);
        summary = normalizedText(summary);
        confidence = normalizedConfidence(confidence);
        candidateIntents = toImmutableCandidateIntents(candidateIntents);
    }

    public static SemanticAnalysisResult empty() {
        return EMPTY;
    }

    public boolean hasSuggestedSkill() {
        return hasText(suggestedSkill);
    }

    public boolean hasRewrittenInput() {
        return hasText(rewrittenInput);
    }

    public String routingInput(String originalInput) {
        return hasRewrittenInput() ? rewrittenInput : (originalInput == null ? "" : originalInput);
    }

    public boolean isConfident(double threshold) {
        return effectiveConfidence() >= threshold;
    }

    public double effectiveConfidence() {
        double candidateScore = confidenceForSkill(suggestedSkill);
        return Math.max(confidence, candidateScore);
    }

    public double confidenceForSkill(String skillName) {
        if (skillName == null || skillName.isBlank() || candidateIntents.isEmpty()) {
            return 0.0;
        }
        String normalized = skillName.trim();
        return candidateIntents.stream()
                .filter(candidate -> normalized.equals(candidate.intent()))
                .mapToDouble(CandidateIntent::confidence)
                .max()
                .orElse(0.0);
    }

    public Map<String, Object> asAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(8);
        attributes.put(ATTR_ANALYSIS_SOURCE, emptyIfNull(source));
        attributes.put(ATTR_INTENT, emptyIfNull(intent));
        attributes.put(ATTR_REWRITTEN_INPUT, emptyIfNull(rewrittenInput));
        attributes.put(ATTR_SUGGESTED_SKILL, emptyIfNull(suggestedSkill));
        attributes.put(ATTR_CONFIDENCE, effectiveConfidence());
        if (hasText(summary)) {
            attributes.put(ATTR_SUMMARY, summary);
        }
        if (!payload.isEmpty()) {
            attributes.put(ATTR_PAYLOAD, payload);
        }
        if (!keywords.isEmpty()) {
            attributes.put(ATTR_KEYWORDS, keywords);
        }
        if (!candidateIntents.isEmpty()) {
            attributes.put(ATTR_CANDIDATE_INTENTS, candidateIntents);
        }
        return Collections.unmodifiableMap(attributes);
    }

    public String toPromptSummary() {
        StringBuilder prompt = new StringBuilder(256);
        if (hasText(intent)) {
            prompt.append("- intent: ").append(intent).append('\n');
        }
        if (hasText(suggestedSkill)) {
            prompt.append("- suggestedSkill: ").append(suggestedSkill).append('\n');
        }
        if (hasText(rewrittenInput)) {
            prompt.append("- rewrittenInput: ").append(rewrittenInput).append('\n');
        }
        if (!keywords.isEmpty()) {
            prompt.append("- keywords: ").append(String.join(", ", keywords)).append('\n');
        }
        if (hasText(this.summary)) {
            prompt.append("- summary: ").append(this.summary).append('\n');
        }
        if (!payload.isEmpty()) {
            prompt.append("- payload: ").append(toStablePayloadString(payload)).append('\n');
        }
        if (prompt.isEmpty()) {
            return "";
        }
        prompt.append("- source: ").append(source == null ? "unknown" : source).append('\n');
        prompt.append("- confidence: ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", effectiveConfidence()))
                .append('\n');
        return prompt.toString();
    }

    private static Map<String, Object> toImmutablePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static List<String> toImmutableKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        return List.copyOf(keywords);
    }

    private static List<CandidateIntent> toImmutableCandidateIntents(List<CandidateIntent> candidateIntents) {
        if (candidateIntents == null || candidateIntents.isEmpty()) {
            return List.of();
        }
        List<CandidateIntent> normalized = new java.util.ArrayList<>();
        for (CandidateIntent candidate : candidateIntents) {
            if (candidate == null || !hasText(candidate.intent())) {
                continue;
            }
            normalized.add(new CandidateIntent(candidate.intent().trim(), normalizedConfidence(candidate.confidence())));
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static String toStablePayloadString(Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        builder.append('}');
        return builder.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String normalizedText(String value) {
        return value == null ? "" : value.trim();
    }

    private static double normalizedConfidence(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record CandidateIntent(String intent, double confidence) {
    }
}

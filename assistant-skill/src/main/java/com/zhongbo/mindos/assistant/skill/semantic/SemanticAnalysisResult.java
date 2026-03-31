package com.zhongbo.mindos.assistant.skill.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticAnalysisResult(String source,
                                     String intent,
                                     String rewrittenInput,
                                     String suggestedSkill,
                                     Map<String, Object> payload,
                                     List<String> keywords,
                                     double confidence) {

    public SemanticAnalysisResult {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
        keywords = List.copyOf(keywords == null ? List.of() : keywords);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static SemanticAnalysisResult empty() {
        return new SemanticAnalysisResult("disabled", "", "", "", Map.of(), List.of(), 0.0);
    }

    public boolean hasSuggestedSkill() {
        return suggestedSkill != null && !suggestedSkill.isBlank();
    }

    public boolean hasRewrittenInput() {
        return rewrittenInput != null && !rewrittenInput.isBlank();
    }

    public String routingInput(String originalInput) {
        return hasRewrittenInput() ? rewrittenInput : (originalInput == null ? "" : originalInput);
    }

    public boolean isConfident(double threshold) {
        return confidence >= threshold;
    }

    public Map<String, Object> asAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("semanticAnalysisSource", source == null ? "" : source);
        attributes.put("semanticIntent", intent == null ? "" : intent);
        attributes.put("semanticRewrittenInput", rewrittenInput == null ? "" : rewrittenInput);
        attributes.put("semanticSuggestedSkill", suggestedSkill == null ? "" : suggestedSkill);
        attributes.put("semanticConfidence", confidence);
        if (!payload.isEmpty()) {
            attributes.put("semanticPayload", payload);
        }
        if (!keywords.isEmpty()) {
            attributes.put("semanticKeywords", keywords);
        }
        return Collections.unmodifiableMap(attributes);
    }

    public String toPromptSummary() {
        StringBuilder summary = new StringBuilder();
        if (intent != null && !intent.isBlank()) {
            summary.append("- intent: ").append(intent).append('\n');
        }
        if (suggestedSkill != null && !suggestedSkill.isBlank()) {
            summary.append("- suggestedSkill: ").append(suggestedSkill).append('\n');
        }
        if (rewrittenInput != null && !rewrittenInput.isBlank()) {
            summary.append("- rewrittenInput: ").append(rewrittenInput).append('\n');
        }
        if (!keywords.isEmpty()) {
            summary.append("- keywords: ").append(String.join(", ", keywords)).append('\n');
        }
        if (!payload.isEmpty()) {
            summary.append("- payload: ").append(payload).append('\n');
        }
        if (summary.length() == 0) {
            return "";
        }
        summary.append("- source: ").append(source == null ? "unknown" : source).append('\n');
        summary.append("- confidence: ").append(String.format(java.util.Locale.ROOT, "%.2f", confidence)).append('\n');
        return summary.toString();
    }
}

package com.zhongbo.mindos.assistant.skill.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SemanticDecisionPromptBuilder {

    private static final int MAX_USER_INPUT_CHARS = 360;
    private static final int MAX_MEMORY_CONTEXT_CHARS = 900;
    private static final int MAX_PROFILE_LINES = 6;
    private static final int MAX_PROFILE_VALUE_CHARS = 96;
    private static final int MAX_TOOL_LINES = 8;
    private static final int MAX_TOOL_LINE_CHARS = 120;

    String buildPrompt(String userInput,
                       String memoryContext,
                       Map<String, Object> profileContext,
                       List<String> availableSkillSummaries,
                       SemanticAnalysisResult baseline) {
        StringBuilder prompt = new StringBuilder(4096);
        prompt.append("You are Hermes semantic decision analyzer for MindOS.\n");
        prompt.append("You are not a chat assistant. Do not answer the user.\n");
        prompt.append("Return strict JSON only. No markdown. No explanation. No code fences.\n");
        prompt.append("Required JSON keys: intent, rewrittenInput, suggestedSkill, summary, confidence, payload, keywords.\n");
        prompt.append("Optional JSON keys: candidate_intents, target, params.\n");
        prompt.append("If you use target or params, they must mean the same thing as suggestedSkill or payload.\n");
        prompt.append("Rules:\n");
        prompt.append("1. Single decision only.\n");
        prompt.append("2. Use only confirmed information from USER_INPUT, CONFIRMED_MEMORY_AND_CONTEXT, PROFILE_CONTEXT, and BASELINE_ROUTING_HINT.\n");
        prompt.append("3. Never invent required params; partial payload is better than fabricated values.\n");
        prompt.append("4. Only use skill names listed in AVAILABLE_TOOLS.\n");
        prompt.append("5. Prefer capability-level targets over raw implementation names when AVAILABLE_TOOLS exposes a capability alias.\n");
        prompt.append("6. Prefer docs/search tools for docs/manual/API requests and search tools for realtime news/weather/market/travel requests.\n");
        prompt.append("7. For short continuations, use recent context when the thread is clear.\n");
        prompt.append("8. Distinguish execution, planning, blocker-report, progress-report, and decision-adjustment on ongoing tasks.\n");
        prompt.append("9. If the user only reports status, pressure, or blockers, prefer suggestedSkill empty unless a tool is clearly requested.\n");
        prompt.append("10. If interpretations are close, lower confidence and keep candidate_intents to at most 3.\n");
        prompt.append("Example:\n");
        prompt.append("{\"intent\":\"task_create\",\"rewrittenInput\":\"创建一个待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"summary\":\"用户要创建待办并记录截止时间\",\"confidence\":0.92,\"payload\":{\"task\":\"提交周报\",\"dueDate\":\"周五前\"},\"keywords\":[\"待办\",\"周报\",\"周五\"]}\n");
        prompt.append('\n');

        appendSection(prompt, "USER_INPUT", capText(userInput, MAX_USER_INPUT_CHARS));
        appendSection(prompt, "CONFIRMED_MEMORY_AND_CONTEXT", capText(memoryContext, MAX_MEMORY_CONTEXT_CHARS));
        appendSection(prompt, "PROFILE_CONTEXT", summarizeProfileContext(profileContext));
        appendSection(prompt, "BASELINE_ROUTING_HINT", summarizeBaseline(baseline));
        appendSection(prompt, "SEARCH_PRIORITY_ORDER", summarizeSearchPriority(profileContext));
        appendSection(prompt, "AVAILABLE_TOOLS", summarizeAvailableTools(availableSkillSummaries, userInput));

        prompt.append("Now output JSON only.\n");
        return prompt.toString();
    }

    private void appendSection(StringBuilder prompt, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        prompt.append(title).append(":\n").append(content).append("\n\n");
    }

    private String summarizeBaseline(SemanticAnalysisResult baseline) {
        if (baseline == null || baseline == SemanticAnalysisResult.empty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        if (!text(baseline.intent()).isBlank()) {
            lines.add("- intent: " + text(baseline.intent()));
        }
        if (!text(baseline.suggestedSkill()).isBlank()) {
            lines.add("- suggestedSkill: " + text(baseline.suggestedSkill()));
        }
        lines.add("- stage: " + baseline.intentPhase()
                + ", state=" + baseline.intentState()
                + ", thread=" + baseline.threadRelation());
        if (baseline.toolRequired()) {
            lines.add("- toolRequired: true");
        }
        if (!"none".equals(baseline.memoryOperation())) {
            lines.add("- memoryOperation: " + baseline.memoryOperation());
        }
        if (!text(baseline.taskFocus()).isBlank()) {
            lines.add("- taskFocus: " + text(baseline.taskFocus()));
        }
        if (!baseline.payload().isEmpty()) {
            lines.add("- payload: " + summarizeMap(baseline.payload(), 120));
        }
        if (baseline.effectiveConfidence() > 0.0d) {
            lines.add("- confidence: " + String.format(Locale.ROOT, "%.2f", baseline.effectiveConfidence()));
        }
        String candidateSummary = baseline.hasAmbiguousSkillChoice() ? summarizeCandidateIntents(baseline) : "";
        if (!candidateSummary.isBlank() && baseline.effectiveConfidence() < 0.86d) {
            lines.add("- candidateIntents: " + candidateSummary);
        }
        if (lines.isEmpty()) {
            return "";
        }
        lines.add("- note: weak hint only");
        return String.join("\n", lines.stream().limit(7).toList());
    }

    private String summarizeProfileContext(Map<String, Object> profileContext) {
        if (profileContext == null || profileContext.isEmpty()) {
            return "(empty)";
        }
        List<String> lines = profileContext.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> !"searchPriorityOrder".equals(entry.getKey()))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .limit(MAX_PROFILE_LINES)
                .map(entry -> "- " + entry.getKey() + ": " + capText(summarizeValue(entry.getValue()), MAX_PROFILE_VALUE_CHARS))
                .toList();
        return lines.isEmpty() ? "(empty)" : String.join("\n", lines);
    }

    private String summarizeSearchPriority(Map<String, Object> profileContext) {
        if (profileContext == null || profileContext.isEmpty()) {
            return "";
        }
        Object raw = profileContext.get("searchPriorityOrder");
        if (raw instanceof List<?> values) {
            List<String> parsed = values.stream()
                    .map(this::text)
                    .filter(value -> !value.isBlank())
                    .toList();
            return parsed.isEmpty() ? "" : String.join(" > ", parsed);
        }
        String single = text(raw);
        return single.isBlank() ? "" : single;
    }

    private String summarizeAvailableTools(List<String> availableSkillSummaries, String userInput) {
        List<String> skills = availableSkillSummaries == null ? List.of() : availableSkillSummaries;
        if (skills.isEmpty()) {
            return "(none)";
        }
        String normalizedInput = normalizeForMatch(userInput);
        List<RankedToolSummary> ranked = new ArrayList<>();
        for (int index = 0; index < skills.size(); index++) {
            ranked.add(new RankedToolSummary(
                    index,
                    compactToolSummary(text(skills.get(index))),
                    toolRelevance(normalizedInput, text(skills.get(index)))
            ));
        }
        ranked.sort(Comparator.comparingInt(RankedToolSummary::score).reversed()
                .thenComparingInt(RankedToolSummary::index));
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_TOOL_LINES, ranked.size()); index++) {
            lines.add((index + 1) + ". " + capText(ranked.get(index).summary(), MAX_TOOL_LINE_CHARS));
        }
        if (ranked.size() > MAX_TOOL_LINES) {
            lines.add("... (+" + (ranked.size() - MAX_TOOL_LINES) + " more)");
        }
        return String.join("\n", lines);
    }

    private String compactToolSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String[] segments = summary.split("\\s*\\|\\s*");
        String headline = segments.length == 0 ? "" : text(segments[0]);
        List<String> essentials = new ArrayList<>();
        for (int index = 1; index < segments.length; index++) {
            String segment = text(segments[index]);
            if (segment.startsWith("required=") || segment.startsWith("oneOf=")) {
                essentials.add(segment);
            }
        }
        if (essentials.isEmpty()) {
            return headline;
        }
        return headline + " | " + String.join(" | ", essentials);
    }

    private int toolRelevance(String normalizedInput, String summary) {
        if (normalizedInput == null || normalizedInput.isBlank() || summary == null || summary.isBlank()) {
            return 0;
        }
        String normalizedSummary = normalizeForMatch(summary);
        if (normalizedSummary.isBlank()) {
            return 0;
        }
        if (normalizedSummary.contains(normalizedInput)) {
            return 10;
        }
        int score = 0;
        for (String token : normalizedInput.split(" ")) {
            if (token.isBlank() || token.length() < 2) {
                continue;
            }
            if (normalizedSummary.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String summarizeCandidateIntents(SemanticAnalysisResult baseline) {
        if (baseline == null || baseline.candidateIntents().isEmpty()) {
            return "";
        }
        return baseline.candidateIntents().stream()
                .limit(3)
                .map(candidate -> text(candidate.intent()) + "="
                        + String.format(Locale.ROOT, "%.2f", candidate.confidence()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::text)
                    .filter(item -> !item.isBlank())
                    .limit(8)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            return summarizeMap(map, MAX_PROFILE_VALUE_CHARS);
        }
        return text(value);
    }

    private String summarizeMap(Map<?, ?> map, int maxChars) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(text(entry.getKey())).append('=').append(text(entry.getValue()));
            first = false;
            if (builder.length() >= maxChars) {
                break;
            }
        }
        builder.append('}');
        return capText(builder.toString(), maxChars);
    }

    private String capText(String value, int maxChars) {
        String normalized = text(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeForMatch(String value) {
        return text(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private record RankedToolSummary(int index, String summary, int score) {
    }
}

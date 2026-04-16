package com.zhongbo.mindos.assistant.skill.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SemanticDecisionPromptBuilder {

    private static final int MAX_USER_INPUT_CHARS = 400;
    private static final int MAX_MEMORY_CONTEXT_CHARS = 1800;
    private static final int MAX_PROFILE_LINES = 10;
    private static final int MAX_PROFILE_VALUE_CHARS = 160;
    private static final int MAX_TOOL_LINES = 16;
    private static final int MAX_TOOL_LINE_CHARS = 180;

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
        prompt.append("If you use target, it must mean the same thing as suggestedSkill.\n");
        prompt.append("If you use params, it must mean the same thing as payload.\n");
        prompt.append('\n');
        prompt.append("Decision rules:\n");
        prompt.append("1. Single decision only: choose at most one skill candidate as suggestedSkill.\n");
        prompt.append("2. Strong skill bias: if a registered skill can solve the request, prefer that skill over leaving suggestedSkill empty.\n");
        prompt.append("3. Use only confirmed information from USER_INPUT, CONFIRMED_MEMORY_AND_CONTEXT, PROFILE_CONTEXT, and BASELINE_ROUTING_HINT.\n");
        prompt.append("4. Never invent required params. If params are missing, keep payload partial instead of fabricating values.\n");
        prompt.append("5. Only use skill names listed in AVAILABLE_TOOLS.\n");
        prompt.append("6. For docs, API, SDK, manual, guide, official documentation requests, prefer docs/search tools over generic chat.\n");
        prompt.append("7. For realtime requests such as news, weather, market, travel, latest updates, prefer search tools over generic chat.\n");
        prompt.append("8. For short continuation inputs like continue, 再来一次, 继续按刚才方式, rely on recent context and recent successful skills when clear.\n");
        prompt.append("9. candidate_intents should contain at most 3 alternatives sorted by confidence descending.\n");
        prompt.append("10. If no tool fits, leave suggestedSkill empty and payload as {}.\n");
        prompt.append("11. Distinguish execution, planning, blocker-report, progress-report, and decision-adjustment turns when the user is working on an ongoing task.\n");
        prompt.append("12. If the user is reporting progress or a blocker on an active task, keep suggestedSkill empty unless a tool is clearly requested.\n");
        prompt.append("13. If the user clearly switches to another matter, do not force the previous task thread.\n");
        prompt.append('\n');
        prompt.append("JSON shape example:\n");
        prompt.append("{\"intent\":\"task_create\",\"rewrittenInput\":\"创建一个待办：提交周报\",\"suggestedSkill\":\"todo.create\",\"summary\":\"用户要创建待办并记录截止时间\",\"confidence\":0.92,\"payload\":{\"task\":\"提交周报\",\"dueDate\":\"周五前\"},\"keywords\":[\"待办\",\"周报\",\"周五\"],\"candidate_intents\":[{\"intent\":\"todo.create\",\"confidence\":0.92},{\"intent\":\"calendar.lookup\",\"confidence\":0.21}]}\n");
        prompt.append("{\"intent\":\"docs_lookup\",\"target\":\"mcp.docs.searchDocs\",\"summary\":\"查询官方文档\",\"confidence\":0.95,\"params\":{\"query\":\"Spring Boot RestClient official docs\"},\"keywords\":[\"Spring Boot\",\"RestClient\",\"docs\"]}\n");
        prompt.append('\n');

        appendSection(prompt, "USER_INPUT", capText(userInput, MAX_USER_INPUT_CHARS));
        appendSection(prompt, "CONFIRMED_MEMORY_AND_CONTEXT", capText(memoryContext, MAX_MEMORY_CONTEXT_CHARS));
        appendSection(prompt, "PROFILE_CONTEXT", summarizeProfileContext(profileContext));
        appendSection(prompt, "BASELINE_ROUTING_HINT", summarizeBaseline(baseline));
        appendSection(prompt, "SEARCH_PRIORITY_ORDER", summarizeSearchPriority(profileContext));
        appendSection(prompt, "AVAILABLE_TOOLS", summarizeAvailableTools(availableSkillSummaries));

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
        lines.add("- intentType: " + baseline.intentType());
        if (!text(baseline.suggestedSkill()).isBlank()) {
            lines.add("- suggestedSkill: " + text(baseline.suggestedSkill()));
        }
        lines.add("- toolRequired: " + baseline.toolRequired());
        lines.add("- contextScope: " + baseline.contextScope());
        lines.add("- intentState: " + baseline.intentState());
        lines.add("- intentPhase: " + baseline.intentPhase());
        lines.add("- threadRelation: " + baseline.threadRelation());
        lines.add("- followUpMode: " + baseline.followUpMode());
        if (!"none".equals(baseline.memoryOperation())) {
            lines.add("- memoryOperation: " + baseline.memoryOperation());
        }
        if (!text(baseline.taskFocus()).isBlank()) {
            lines.add("- taskFocus: " + text(baseline.taskFocus()));
        }
        if (!baseline.payload().isEmpty()) {
            lines.add("- payload: " + summarizeMap(baseline.payload(), 220));
        }
        if (!text(baseline.summary()).isBlank()) {
            lines.add("- summary: " + text(baseline.summary()));
        }
        if (baseline.effectiveConfidence() > 0.0d) {
            lines.add("- confidence: " + String.format(Locale.ROOT, "%.2f", baseline.effectiveConfidence()));
        }
        if (lines.isEmpty()) {
            return "";
        }
        lines.add("- note: this is a weak routing hint, not a hard constraint");
        return String.join("\n", lines);
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

    private String summarizeAvailableTools(List<String> availableSkillSummaries) {
        List<String> skills = availableSkillSummaries == null ? List.of() : availableSkillSummaries;
        if (skills.isEmpty()) {
            return "(none)";
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_TOOL_LINES, skills.size()); index++) {
            lines.add((index + 1) + ". " + capText(text(skills.get(index)), MAX_TOOL_LINE_CHARS));
        }
        if (skills.size() > MAX_TOOL_LINES) {
            lines.add("... (+" + (skills.size() - MAX_TOOL_LINES) + " more)");
        }
        return String.join("\n", lines);
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
}

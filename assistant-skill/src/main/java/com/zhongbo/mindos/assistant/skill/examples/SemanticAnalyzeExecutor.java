package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SemanticAnalyzeExecutor {

    private final SemanticAnalysisService semanticAnalysisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    SemanticAnalyzeExecutor(SemanticAnalysisService semanticAnalysisService) {
        this.semanticAnalysisService = semanticAnalysisService;
    }

    SkillResult execute(String skillName, SkillContext context) {
        SemanticAnalyzeRequest request = SemanticAnalyzeRequest.from(context);
        SemanticAnalysisResult result = analyze(request);
        if ("json".equalsIgnoreCase(request.responseFormat())) {
            return SkillResult.success(skillName, renderJson(result, request.input(), request.memoryContext()));
        }
        return SkillResult.success(skillName, renderText(result, request.input()));
    }

    private SemanticAnalysisResult analyze(SemanticAnalyzeRequest request) {
        if (semanticAnalysisService == null) {
            return SemanticAnalysisResult.empty();
        }
        return semanticAnalysisService.analyze(
                request.userId(),
                request.input(),
                request.memoryContext(),
                request.profile(),
                request.availableSkills()
        );
    }

    private String renderText(SemanticAnalysisResult result, String targetInput) {
        StringBuilder output = new StringBuilder("[semantic.analyze]\n");
        output.append("原始输入: ").append(targetInput).append('\n');
        output.append("意图: ").append(blankAsDefault(result.intent(), "未识别明确意图")).append('\n');
        output.append("改写请求: ").append(blankAsDefault(result.rewrittenInput(), targetInput)).append('\n');
        output.append("摘要: ").append(blankAsDefault(result.summary(), "无")).append('\n');
        output.append("关键词: ").append(result.keywords().isEmpty() ? "-" : String.join(", ", result.keywords())).append('\n');
        output.append("候选意图: ").append(renderCandidateIntentLabels(result)).append('\n');
        output.append("来源: ").append(blankAsDefault(result.source(), "heuristic")).append('\n');
        output.append("置信度: ").append(Math.round(result.confidence() * 100)).append("%");
        if (!result.payload().isEmpty()) {
            output.append("\n参数提示: ").append(result.payload());
        }
        return output.toString();
    }

    private String renderJson(SemanticAnalysisResult result, String targetInput, String memoryContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", blankAsDefault(result.intent(), "unknown"));
        payload.put("rewrittenInput", blankAsDefault(result.rewrittenInput(), targetInput));
        payload.put("summary", blankAsDefault(result.summary(), ""));
        payload.put("payloadHints", result.payload());
        payload.put("keywords", result.keywords());
        payload.put("confidence", result.confidence());
        payload.put("source", blankAsDefault(result.source(), "heuristic"));
        payload.put("candidateIntents", buildCandidateIntents(result));
        payload.put("contextSummary", buildContextSummary(memoryContext, result));
        payload.put("input", targetInput);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"intent\":\"unknown\",\"input\":\"" + targetInput.replace("\"", "\\\"") + "\"}";
        }
    }

    private List<Map<String, Object>> buildCandidateIntents(SemanticAnalysisResult result) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidate(candidates, result.intent(), result.confidence());
        addCandidate(candidates, result.suggestedSkill(), Math.max(result.confidence() - 0.1, 0.0));
        return List.copyOf(candidates);
    }

    private String renderCandidateIntentLabels(SemanticAnalysisResult result) {
        List<Map<String, Object>> candidates = buildCandidateIntents(result);
        if (candidates.isEmpty()) {
            return "-";
        }
        return candidates.stream()
                .map(candidate -> asString(candidate.get("intent")))
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private void addCandidate(List<Map<String, Object>> candidates, String intent, double confidence) {
        if (intent == null || intent.isBlank()) {
            return;
        }
        for (Map<String, Object> existing : candidates) {
            if (intent.equals(existing.get("intent"))) {
                return;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("intent", intent);
        row.put("confidence", Math.max(0.0, Math.min(1.0, confidence)));
        candidates.add(Map.copyOf(row));
    }

    private String buildContextSummary(String memoryContext, SemanticAnalysisResult result) {
        if (memoryContext != null && !memoryContext.isBlank()) {
            String compact = memoryContext.replaceAll("\\s+", " ").trim();
            return compact.length() > 80 ? compact.substring(0, 80) + "..." : compact;
        }
        if (result.summary() != null && !result.summary().isBlank()) {
            return result.summary();
        }
        return "暂无相关历史上下文";
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankAsDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record SemanticAnalyzeRequest(String userId,
                                          String input,
                                          String memoryContext,
                                          String responseFormat,
                                          Map<String, Object> profile,
                                          List<String> availableSkills) {

        private static SemanticAnalyzeRequest from(SkillContext context) {
            Map<String, Object> attributes = context == null || context.attributes() == null
                    ? Map.of()
                    : context.attributes();
            String userId = context == null || context.userId() == null ? "" : context.userId();
            String input = context == null || context.input() == null ? "" : context.input().trim();
            if (attributes.containsKey("input")) {
                String explicitInput = valueAsString(attributes.get("input"));
                if (!explicitInput.isBlank()) {
                    input = explicitInput;
                }
            }
            return new SemanticAnalyzeRequest(
                    userId,
                    input,
                    valueAsString(attributes.get("memoryContext")),
                    valueAsString(attributes.get("responseFormat")),
                    normalizeProfile(attributes.get("profile")),
                    normalizeAvailableSkills(attributes.get("availableSkills"))
            );
        }

        private static String valueAsString(Object value) {
            return value == null ? "" : String.valueOf(value).trim();
        }

        private static Map<String, Object> normalizeProfile(Object raw) {
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                return Map.copyOf(normalized);
            }
            return Map.of();
        }

        private static List<String> normalizeAvailableSkills(Object raw) {
            if (raw instanceof List<?> values) {
                return values.stream()
                        .map(String::valueOf)
                        .filter(value -> !value.isBlank())
                        .toList();
            }
            return List.of();
        }
    }
}

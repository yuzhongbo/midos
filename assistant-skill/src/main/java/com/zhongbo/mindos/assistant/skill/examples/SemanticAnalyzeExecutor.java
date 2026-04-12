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
        String targetInput = resolveTargetInput(context);
        String memoryContext = asString(attributes(context).get("memoryContext"));
        if (!isAuthorized(context)) {
            if ("json".equalsIgnoreCase(asString(attributes(context).get("responseFormat")))) {
                return SkillResult.success(skillName, renderAccessDeniedJson(context == null ? "" : context.userId(), extractActorId(attributes(context))));
            }
            return SkillResult.success(skillName, "[semantic.analyze]\n权限不足：当前请求的操作者与 userId 不一致，请使用同一用户身份或先完成授权挑战。");
        }
        SemanticAnalysisResult result = analyze(targetInput, memoryContext, context);
        if ("json".equalsIgnoreCase(asString(attributes(context).get("responseFormat")))) {
            return SkillResult.success(skillName, renderJson(result, targetInput, memoryContext));
        }
        return SkillResult.success(skillName, renderText(result, targetInput));
    }

    private SemanticAnalysisResult analyze(String targetInput, String memoryContext, SkillContext context) {
        if (semanticAnalysisService == null) {
            return SemanticAnalysisResult.empty();
        }
        SemanticAnalysisResult result = semanticAnalysisService.analyze(
                context == null ? "" : context.userId(),
                targetInput,
                memoryContext,
                extractProfile(attributes(context).get("profile")),
                extractSkillSummaries(attributes(context).get("availableSkills"))
        );
        if (result.confidence() <= 0.0) {
            return semanticAnalysisService.analyzeHeuristically(targetInput);
        }
        return result;
    }

    private String resolveTargetInput(SkillContext context) {
        Map<String, Object> attributes = attributes(context);
        Object explicit = attributes.get("input");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit).trim();
        }
        Object targetInput = attributes.get("targetInput");
        if (targetInput != null && !String.valueOf(targetInput).isBlank()) {
            return String.valueOf(targetInput).trim();
        }
        return "";
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

    private boolean isAuthorized(SkillContext context) {
        String actorId = extractActorId(attributes(context));
        if (actorId.isBlank() || actorId.equals(context == null ? "" : context.userId())) {
            return true;
        }
        Map<String, Object> attributes = attributes(context);
        return isTruthy(attributes.get("securityChallengePassed"))
                || isTruthy(attributes.get("adminApproved"))
                || isTruthy(attributes.get("allowDelegatedAccess"));
    }

    private String extractActorId(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        String actor = asString(attributes.get("actorId"));
        if (!actor.isBlank()) {
            return actor;
        }
        actor = asString(attributes.get("requestUserId"));
        if (!actor.isBlank()) {
            return actor;
        }
        return asString(attributes.get("operatorId"));
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(asString(value)) || "1".equals(asString(value));
    }

    private String renderAccessDeniedJson(String userId, String actorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", "access_denied");
        payload.put("rewrittenInput", "");
        payload.put("summary", "权限校验失败：actor 与 userId 不匹配");
        payload.put("payloadHints", Map.of());
        payload.put("keywords", List.of());
        payload.put("confidence", 0.0);
        payload.put("source", "security");
        payload.put("candidateIntents", List.of());
        payload.put("userId", asString(userId));
        payload.put("actorId", asString(actorId));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"intent\":\"access_denied\",\"confidence\":0.0}";
        }
    }

    private Map<String, Object> extractProfile(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            return normalized;
        }
        return Map.of();
    }

    private List<String> extractSkillSummaries(Object raw) {
        if (raw instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> attributes(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return Map.of();
        }
        return context.attributes();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankAsDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

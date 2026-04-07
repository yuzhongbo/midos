package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SemanticAnalyzeSkill implements Skill {

    private final SemanticAnalysisService semanticAnalysisService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SemanticAnalyzeSkill(SemanticAnalysisService semanticAnalysisService,
                                @Lazy SkillRegistry skillRegistry) {
        this.semanticAnalysisService = semanticAnalysisService;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String name() {
        return "semantic.analyze";
    }

    @Override
    public String description() {
        return "Analyzes user intent, rewrites the request semantically, and suggests local skill routing or downstream LLM handling.";
    }

    @Override
    public List<String> routingKeywords() {
        return List.of("semantic", "semantic.analyze", "语义分析", "分析我的语义");
    }

    @Override
    public boolean supports(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String normalized = input.trim().toLowerCase();
        return normalized.startsWith("semantic ")
                || normalized.startsWith("semantic.analyze")
                || normalized.contains("语义分析")
                || normalized.contains("分析我的语义");
    }

    @Override
    public SkillResult run(SkillContext context) {
        String targetInput = resolveTargetInput(context);
        String memoryContext = asString(context.attributes().get("memoryContext"));
        if (!isAuthorized(context)) {
            if ("json".equalsIgnoreCase(asString(context.attributes().get("responseFormat")))) {
                return SkillResult.success(name(), renderAccessDeniedJson(context.userId(), extractActorId(context.attributes())));
            }
            return SkillResult.success(name(), "[semantic.analyze]\n权限不足：当前请求的操作者与 userId 不一致，请使用同一用户身份或先完成授权挑战。");
        }
        SemanticAnalysisResult result = semanticAnalysisService == null
                ? fallbackAnalysis(targetInput)
                : semanticAnalysisService.analyze(
                context.userId(),
                targetInput,
                memoryContext,
                extractProfile(context.attributes().get("profile")),
                extractSkillSummaries(context.attributes().get("availableSkills"))
        );
        if (result.confidence() <= 0.0) {
            result = fallbackAnalysis(targetInput);
        }
        if ("json".equalsIgnoreCase(asString(context.attributes().get("responseFormat")))) {
            return SkillResult.success(name(), renderJson(result, targetInput, memoryContext));
        }
        return SkillResult.success(name(), renderText(result, targetInput));
    }

    private String resolveTargetInput(SkillContext context) {
        Object explicit = context.attributes().get("input");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit).trim();
        }
        String input = context.input() == null ? "" : context.input().trim();
        if (input.toLowerCase().startsWith("semantic.analyze")) {
            return input.substring("semantic.analyze".length()).trim();
        }
        if (input.toLowerCase().startsWith("semantic ")) {
            return input.substring("semantic ".length()).trim();
        }
        return input;
    }

    private String renderText(SemanticAnalysisResult result, String targetInput) {
        StringBuilder output = new StringBuilder("[semantic.analyze]\n");
        output.append("原始输入: ").append(targetInput).append('\n');
        output.append("意图: ").append(blankAsDefault(result.intent(), "未识别明确本地技能意图")).append('\n');
        output.append("建议技能: ").append(blankAsDefault(result.suggestedSkill(), "交给后续模型")).append('\n');
        output.append("改写请求: ").append(blankAsDefault(result.rewrittenInput(), targetInput)).append('\n');
        output.append("关键词: ").append(result.keywords().isEmpty() ? "-" : String.join(", ", result.keywords())).append('\n');
        output.append("来源: ").append(blankAsDefault(result.source(), "heuristic")).append('\n');
        output.append("置信度: ").append(Math.round(result.confidence() * 100)).append("%");
        if (!result.payload().isEmpty()) {
            output.append("\n建议参数: ").append(result.payload());
        }
        return output.toString();
    }

    private String renderJson(SemanticAnalysisResult result, String targetInput, String memoryContext) {
        List<Map<String, Object>> candidateIntents = buildCandidateIntents(result, targetInput);
        String validatedIntent = selectBestIntentByContext(result.suggestedSkill(), candidateIntents, memoryContext);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", validatedIntent);
        payload.put("params", result.payload());
        payload.put("priority", derivePriority(result, targetInput));
        payload.put("context_summary", buildContextSummary(memoryContext, result));
        payload.put("cloud_enhance_needed", shouldCloudEnhance(targetInput, result));
        payload.put("candidate_intents", candidateIntents);

        // Backward-compatible fields retained for existing integrations.
        payload.put("input", targetInput);
        payload.put("rewrittenInput", result.rewrittenInput());
        payload.put("suggestedSkill", result.suggestedSkill());
        payload.put("payload", result.payload());
        payload.put("keywords", result.keywords());
        payload.put("confidence", result.confidence());
        payload.put("source", result.source());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"input\":\"" + targetInput.replace("\"", "\\\"") + "\"}";
        }
    }

    private String resolveIntentCode(SemanticAnalysisResult result, String targetInput) {
        if (result.suggestedSkill() != null && !result.suggestedSkill().isBlank() && isRegisteredSkill(result.suggestedSkill())) {
            return result.suggestedSkill();
        }
        String normalized = targetInput == null ? "" : targetInput.toLowerCase(Locale.ROOT);
        if ((normalized.contains("天气") || normalized.contains("weather")) && isRegisteredSkill("mcp.bravesearch.webSearch")) {
            return "mcp.bravesearch.webSearch";
        }
        if ((normalized.contains("课程") || normalized.contains("报名") || normalized.contains("预订")) && isRegisteredSkill("teaching.plan")) {
            return "teaching.plan";
        }
        if ((normalized.contains("日程") || normalized.contains("schedule") || normalized.contains("规划")) && isRegisteredSkill("teaching.plan")) {
            return "teaching.plan";
        }
        if (isRegisteredSkill("clarification.ask")) {
            return "clarification.ask";
        }
        return result.suggestedSkill() == null ? "" : result.suggestedSkill();
    }

    private int derivePriority(SemanticAnalysisResult result, String targetInput) {
        String normalized = targetInput == null ? "" : targetInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("所有") || normalized.contains("全部") || normalized.contains("跨任务") || normalized.contains("优化")
                || normalized.contains("复杂") || normalized.contains("综合")) {
            return 5;
        }
        if (normalized.contains("规划") || normalized.contains("计划") || normalized.contains("方案") || normalized.contains("分析")) {
            return 4;
        }
        if (normalized.contains("预订") || normalized.contains("报名") || normalized.contains("下周") || normalized.contains("安排")) {
            return 3;
        }
        if (result.confidence() >= 0.85 && !result.payload().isEmpty()) {
            return 1;
        }
        return 2;
    }

    private boolean shouldCloudEnhance(String targetInput, SemanticAnalysisResult result) {
        String normalized = targetInput == null ? "" : targetInput.toLowerCase(Locale.ROOT);
        int priority = derivePriority(result, targetInput);
        return priority >= 4 || normalized.contains("总结") || normalized.contains("润色") || normalized.contains("优化");
    }

    private List<Map<String, Object>> buildCandidateIntents(SemanticAnalysisResult result, String targetInput) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidate(candidates, resolveIntentCode(result, targetInput), Math.max(result.confidence(), 0.60));
        String normalized = targetInput == null ? "" : targetInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("天气") || normalized.contains("weather")) {
            addCandidate(candidates, "mcp.bravesearch.webSearch", 0.82);
            addCandidate(candidates, "mcp.qwensearch.webSearch", 0.80);
        }
        if (normalized.contains("新闻") || normalized.contains("热点") || normalized.contains("最新")) {
            addCandidate(candidates, "mcp.bravesearch.webSearch", 0.84);
            addCandidate(candidates, "news_search", 0.72);
        }
        if (normalized.contains("待办") || normalized.contains("提醒")) {
            addCandidate(candidates, "todo.create", 0.86);
        }
        if (normalized.contains("课程") || normalized.contains("学习") || normalized.contains("规划")) {
            addCandidate(candidates, "teaching.plan", 0.78);
        }
        if (normalized.contains("沟通") || normalized.contains("情商")) {
            addCandidate(candidates, "eq.coach", 0.76);
        }
        return candidates;
    }

    private String selectBestIntentByContext(String suggestedSkill,
                                             List<Map<String, Object>> candidates,
                                             String memoryContext) {
        if (candidates.isEmpty()) {
            return isRegisteredSkill("clarification.ask") ? "clarification.ask" : "";
        }
        double bestScore = -1.0;
        String bestIntent = "";
        String normalizedMemory = memoryContext == null ? "" : memoryContext.toLowerCase(Locale.ROOT);
        for (Map<String, Object> candidate : candidates) {
            String intent = asString(candidate.get("intent"));
            if (!isRegisteredSkill(intent)) {
                continue;
            }
            double confidence = candidate.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
            double contextBoost = normalizedMemory.contains(intent.toLowerCase(Locale.ROOT)) ? 0.10 : 0.0;
            if (suggestedSkill != null && suggestedSkill.equals(intent)) {
                contextBoost += 0.08;
            }
            double score = confidence + contextBoost;
            if (score > bestScore) {
                bestScore = score;
                bestIntent = intent;
            }
        }
        if (!bestIntent.isBlank()) {
            return bestIntent;
        }
        return isRegisteredSkill("clarification.ask") ? "clarification.ask" : asString(candidates.get(0).get("intent"));
    }

    private void addCandidate(List<Map<String, Object>> candidates, String intent, double confidence) {
        if (intent == null || intent.isBlank() || !isRegisteredSkill(intent)) {
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

    private boolean isRegisteredSkill(String skillName) {
        if (skillName == null || skillName.isBlank() || skillRegistry == null) {
            return false;
        }
        return skillRegistry.getSkill(skillName).isPresent();
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
        String actorId = extractActorId(context.attributes());
        if (actorId.isBlank() || actorId.equals(context.userId())) {
            return true;
        }
        return isTruthy(context.attributes().get("securityChallengePassed"))
                || isTruthy(context.attributes().get("adminApproved"))
                || isTruthy(context.attributes().get("allowDelegatedAccess"));
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
        payload.put("params", Map.of());
        payload.put("priority", 0);
        payload.put("context_summary", "权限校验失败：actor 与 userId 不匹配");
        payload.put("cloud_enhance_needed", false);
        payload.put("candidate_intents", List.of());
        payload.put("userId", asString(userId));
        payload.put("actorId", asString(actorId));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"intent\":\"access_denied\",\"priority\":0}";
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

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private SemanticAnalysisResult fallbackAnalysis(String input) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("学习计划") || normalized.contains("教学规划") || normalized.contains("study plan")) {
            return new SemanticAnalysisResult("skill-fallback", "为用户生成学习/教学规划", input, "teaching.plan", Map.of(), List.of("学习计划"), 0.82);
        }
        if (normalized.contains("待办") || normalized.contains("todo") || normalized.contains("提醒") || normalized.contains("截止")) {
            return new SemanticAnalysisResult("skill-fallback", "创建待办或提醒事项", input, "todo.create", Map.of("task", input), List.of("待办", "提醒"), 0.78);
        }
        if (normalized.contains("沟通") || normalized.contains("情商") || normalized.contains("冲突") || normalized.contains("心理分析")) {
            return new SemanticAnalysisResult("skill-fallback", "分析沟通场景并生成情商沟通建议", input, "eq.coach", Map.of("query", input), List.of("沟通", "情商"), 0.78);
        }
        if (normalized.contains("代码") || normalized.contains("接口") || normalized.contains("bug") || normalized.contains("api") || normalized.contains("dto")) {
            return new SemanticAnalysisResult("skill-fallback", "生成或整理代码实现方案", input, "code.generate", Map.of("task", input), List.of("代码", "接口"), 0.76);
        }
        return new SemanticAnalysisResult("skill-fallback", "整理用户原始诉求并建议后续处理方向", input, "", Map.of(), List.of(), 0.4);
    }

    private String blankAsDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

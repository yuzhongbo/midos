package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SemanticAnalyzeSkill implements Skill {

    private final SemanticAnalysisService semanticAnalysisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SemanticAnalyzeSkill(SemanticAnalysisService semanticAnalysisService) {
        this.semanticAnalysisService = semanticAnalysisService;
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
        SemanticAnalysisResult result = semanticAnalysisService == null
                ? fallbackAnalysis(targetInput)
                : semanticAnalysisService.analyze(
                context.userId(),
                targetInput,
                asString(context.attributes().get("memoryContext")),
                extractProfile(context.attributes().get("profile")),
                extractSkillSummaries(context.attributes().get("availableSkills"))
        );
        if (result.confidence() <= 0.0) {
            result = fallbackAnalysis(targetInput);
        }
        if ("json".equalsIgnoreCase(asString(context.attributes().get("responseFormat")))) {
            return SkillResult.success(name(), renderJson(result, targetInput));
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

    private String renderJson(SemanticAnalysisResult result, String targetInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", targetInput);
        payload.put("intent", result.intent());
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

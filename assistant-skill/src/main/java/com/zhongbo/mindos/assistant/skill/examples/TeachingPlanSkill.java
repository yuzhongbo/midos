package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class TeachingPlanSkill implements Skill {

    private static final Logger LOGGER = Logger.getLogger(TeachingPlanSkill.class.getName());

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TeachingPlanSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // Test-friendly constructor.
    TeachingPlanSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "teaching.plan";
    }

    @Override
    public String description() {
        return "Generates a personalized teaching/study plan with multi-dimensional student profile and LLM-backed optimization.";
    }

    @Override
    public boolean supports(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String normalized = input.trim().toLowerCase();
        return normalized.contains("教学规划")
                || normalized.contains("学习计划")
                || normalized.contains("复习计划")
                || normalized.contains("课程规划")
                || normalized.contains("study plan")
                || normalized.contains("teaching plan");
    }

    @Override
    public SkillResult run(SkillContext context) {
        PlanRequest request = buildRequest(context);
        List<String> validationErrors = validateRequest(request);
        if (!validationErrors.isEmpty()) {
            return SkillResult.failure(name(), "[teaching.plan] 输入校验失败: " + String.join("; ", validationErrors));
        }

        Map<String, Object> plan = generatePlanWithLlm(request, context)
                .orElseGet(() -> generateFallbackPlan(request));

        if (!validatePlanSchema(plan)) {
            plan = generateFallbackPlan(request);
        }

        return SkillResult.success(name(), renderPlan(request, plan));
    }

    private PlanRequest buildRequest(SkillContext context) {
        Map<String, Object> attributes = context.attributes();
        String topic = firstNonBlank(asString(attributes, "topic"), inferTopicFromInput(context.input()));

        return new PlanRequest(
                firstNonBlank(asString(attributes, "studentId"), context.userId()),
                topic,
                firstNonBlank(asString(attributes, "gradeOrLevel"), "未指定"),
                firstNonBlank(asString(attributes, "goal"), "夯实基础并稳定提升"),
                clamp(asInt(attributes.get("durationWeeks"), 8), 1, 52),
                clamp(asInt(attributes.get("weeklyHours"), 6), 1, 80),
                asStringList(attributes.get("weakTopics")),
                asStringList(attributes.get("strongTopics")),
                asStringList(attributes.get("learningStyle")),
                asStringList(attributes.get("constraints")),
                asStringList(attributes.get("resourcePreference"))
        );
    }

    private List<String> validateRequest(PlanRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.topic() == null || request.topic().isBlank()) {
            errors.add("topic 不能为空");
        }
        if (request.durationWeeks() <= 0 || request.durationWeeks() > 52) {
            errors.add("durationWeeks 需在 1-52");
        }
        if (request.weeklyHours() <= 0 || request.weeklyHours() > 80) {
            errors.add("weeklyHours 需在 1-80");
        }
        return errors;
    }

    private Optional<Map<String, Object>> generatePlanWithLlm(PlanRequest request, SkillContext context) {
        if (llmClient == null) {
            return Optional.empty();
        }

        try {
            String requestJson = objectMapper.writeValueAsString(request.toPromptPayload());
            String prompt = "你是教学规划专家。仅返回 JSON，不要输出 markdown。"
                    + "输出 schema: {summary:string,coursePath:[{phase:string,focus:[string],weeklyHours:number,milestones:[string]}],"
                    + "weeklyPlan:[{week:number,tasks:[{subject:string,topic:string,durationMin:number,type:string}]}],"
                    + "assessment:{kpi:[string],checkpoints:[string]},riskAlerts:[{level:string,message:string}],adjustmentRules:[string]}。"
                    + "输入学生画像:" + requestJson;

            String reply = llmClient.generateResponse(prompt, buildLlmContext(context, request));
            String jsonBody = extractJsonBody(reply);
            if (jsonBody == null) {
                return Optional.empty();
            }
            Map<String, Object> parsed = objectMapper.readValue(jsonBody, new TypeReference<>() {
            });
            return Optional.of(parsed);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "LLM generation failed for teaching.plan, fallback to local template", ex);
            return Optional.empty();
        }
    }

    private Map<String, Object> buildLlmContext(SkillContext context, PlanRequest request) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        llmContext.put("request", request.toPromptPayload());
        return llmContext;
    }

    private boolean validatePlanSchema(Map<String, Object> plan) {
        if (plan == null) {
            return false;
        }
        if (!isNonBlankString(plan.get("summary"))) {
            return false;
        }
        if (!(plan.get("coursePath") instanceof List<?> coursePath) || coursePath.isEmpty()) {
            return false;
        }
        if (!(plan.get("weeklyPlan") instanceof List<?> weeklyPlan) || weeklyPlan.isEmpty()) {
            return false;
        }
        return plan.get("assessment") instanceof Map<?, ?>;
    }

    private String renderPlan(PlanRequest request, Map<String, Object> plan) {
        StringBuilder output = new StringBuilder();
        output.append("[教学规划]\n");
        output.append("学生ID: ").append(request.studentId()).append('\n');
        output.append("主题: ").append(request.topic()).append('\n');
        output.append("对象: ").append(request.gradeOrLevel()).append('\n');
        output.append("目标: ").append(request.goal()).append('\n');
        output.append("周期: ").append(request.durationWeeks()).append(" 周\n");
        output.append("每周投入: ").append(request.weeklyHours()).append(" 小时\n");
        output.append("薄弱点: ").append(joinOrDefault(request.weakTopics())).append('\n');
        output.append("优势项: ").append(joinOrDefault(request.strongTopics())).append('\n');
        output.append("学习风格: ").append(joinOrDefault(request.learningStyle())).append('\n');
        output.append("约束: ").append(joinOrDefault(request.constraints())).append("\n\n");

        output.append("规划概览: ").append(String.valueOf(plan.getOrDefault("summary", ""))).append("\n\n");
        output.append("阶段安排:\n");
        for (Map<String, Object> phase : asMapList(plan.get("coursePath"))) {
            output.append("- ").append(stringValue(phase.get("phase"), "阶段"))
                    .append(" | 重点: ").append(joinFlexible(phase.get("focus")))
                    .append(" | 每周: ").append(numberValue(phase.get("weeklyHours"), request.weeklyHours())).append(" 小时")
                    .append(" | 里程碑: ").append(joinFlexible(phase.get("milestones")))
                    .append('\n');
        }

        output.append("\n每周建议:\n");
        for (Map<String, Object> week : asMapList(plan.get("weeklyPlan"))) {
            int weekNo = numberValue(week.get("week"), 0);
            output.append("- 第 ").append(weekNo).append(" 周: ");
            List<Map<String, Object>> tasks = asMapList(week.get("tasks"));
            if (tasks.isEmpty()) {
                output.append("按计划执行并复盘");
            } else {
                Map<String, Object> firstTask = tasks.get(0);
                output.append(stringValue(firstTask.get("subject"), "综合"))
                        .append("/")
                        .append(stringValue(firstTask.get("topic"), "专项训练"))
                        .append("(")
                        .append(numberValue(firstTask.get("durationMin"), 60))
                        .append("min)");
            }
            output.append('\n');
        }

        Map<String, Object> assessment = asMap(plan.get("assessment"));
        output.append("\n评估机制: KPI=").append(joinFlexible(assessment.get("kpi")))
                .append("; 检查点=").append(joinFlexible(assessment.get("checkpoints"))).append('\n');
        output.append("风险提示: ").append(joinRiskAlerts(plan.get("riskAlerts"))).append('\n');
        output.append("调整规则: ").append(joinFlexible(plan.get("adjustmentRules")));
        return output.toString();
    }

    private Map<String, Object> generateFallbackPlan(PlanRequest request) {
        int phase1Weeks = Math.max(1, request.durationWeeks() / 3);
        int phase2Weeks = Math.max(1, request.durationWeeks() / 3);
        int phase3Weeks = Math.max(1, request.durationWeeks() - phase1Weeks - phase2Weeks);

        Map<String, Object> phase1 = Map.of(
                "phase", "第 1-" + phase1Weeks + " 周",
                "focus", List.of("基础诊断", "知识补齐"),
                "weeklyHours", Math.max(1, request.weeklyHours() * 4 / 10),
                "milestones", List.of("完成基础测评并建立错题本")
        );
        Map<String, Object> phase2 = Map.of(
                "phase", "第 " + (phase1Weeks + 1) + "-" + (phase1Weeks + phase2Weeks) + " 周",
                "focus", List.of("专题训练", "错题闭环"),
                "weeklyHours", Math.max(1, request.weeklyHours() * 4 / 10),
                "milestones", List.of("专题正确率达到 80%+")
        );
        Map<String, Object> phase3 = Map.of(
                "phase", "第 " + (phase1Weeks + phase2Weeks + 1) + "-" + request.durationWeeks() + " 周",
                "focus", List.of("模拟演练", "冲刺复盘"),
                "weeklyHours", Math.max(1, request.weeklyHours() * 2 / 10),
                "milestones", List.of("形成稳定答题节奏")
        );

        List<Map<String, Object>> weeklyPlan = new ArrayList<>();
        for (int week = 1; week <= Math.min(request.durationWeeks(), 4); week++) {
            weeklyPlan.add(Map.of(
                    "week", week,
                    "tasks", List.of(Map.of(
                            "subject", request.topic(),
                            "topic", week <= 2 ? "基础与核心题型" : "综合训练与复盘",
                            "durationMin", Math.max(60, request.weeklyHours() * 60 / 3),
                            "type", "讲解+练习"
                    ))
            ));
        }

        return Map.of(
                "summary", "以" + request.topic() + "为主线，结合薄弱点和学习风格进行分阶段提升。",
                "coursePath", List.of(phase1, phase2, phase3),
                "weeklyPlan", weeklyPlan,
                "assessment", Map.of(
                        "kpi", List.of("每周完成率", "阶段正确率", "错题回访率"),
                        "checkpoints", List.of("W2", "W4", "W8", "W" + request.durationWeeks())
                ),
                "riskAlerts", List.of(Map.of("level", "medium", "message", "若连续两周未达标，请降低难度并增加复盘时间")),
                "adjustmentRules", List.of("连续两周 KPI 未达标 -> 任务量下调 20% 并增加 1 次专项复盘")
        );
    }

    private String inferTopicFromInput(String input) {
        if (input == null || input.isBlank()) {
            return "通用能力提升";
        }
        String normalized = input
                .replace("教学规划", "")
                .replace("学习计划", "")
                .replace("复习计划", "")
                .replace("课程规划", "")
                .trim();
        return normalized.isBlank() ? "通用能力提升" : normalized;
    }

    private String extractJsonBody(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String trimmed = response.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return null;
        }
        return trimmed.substring(firstBrace, lastBrace + 1);
    }

    private String asString(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String normalized = String.valueOf(value).trim();
        if (normalized.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> rawList) {
            List<String> result = new ArrayList<>();
            for (Object item : rawList) {
                String normalized = String.valueOf(item).trim();
                if (!normalized.isBlank()) {
                    result.add(normalized);
                }
            }
            return List.copyOf(result);
        }
        String raw = String.valueOf(value).trim();
        if (raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,，;；/、]");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        rawMap.forEach((k, v) -> mapped.put(String.valueOf(k), v));
        return mapped;
    }

    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Object item : list) {
            mapped.add(asMap(item));
        }
        return List.copyOf(mapped);
    }

    private String joinOrDefault(List<String> values) {
        return values == null || values.isEmpty() ? "无" : String.join("、", values);
    }

    private String joinFlexible(Object value) {
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                String normalized = String.valueOf(item).trim();
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values.isEmpty() ? "无" : String.join("、", values);
        }
        String normalized = String.valueOf(value == null ? "" : value).trim();
        return normalized.isBlank() ? "无" : normalized;
    }

    private String joinRiskAlerts(Object value) {
        List<Map<String, Object>> alerts = asMapList(value);
        if (alerts.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> alert : alerts) {
            lines.add("[" + stringValue(alert.get("level"), "info") + "] " + stringValue(alert.get("message"), ""));
        }
        return String.join("; ", lines);
    }

    private String stringValue(Object value, String fallback) {
        String normalized = String.valueOf(value == null ? "" : value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String normalized = String.valueOf(value == null ? "" : value).trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record PlanRequest(String studentId,
                               String topic,
                               String gradeOrLevel,
                               String goal,
                               int durationWeeks,
                               int weeklyHours,
                               List<String> weakTopics,
                               List<String> strongTopics,
                               List<String> learningStyle,
                               List<String> constraints,
                               List<String> resourcePreference) {

        Map<String, Object> toPromptPayload() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("studentId", studentId);
            map.put("topic", topic);
            map.put("gradeOrLevel", gradeOrLevel);
            map.put("goal", goal);
            map.put("durationWeeks", durationWeeks);
            map.put("weeklyHours", weeklyHours);
            map.put("weakTopics", weakTopics);
            map.put("strongTopics", strongTopics);
            map.put("learningStyle", learningStyle);
            map.put("constraints", constraints);
            map.put("resourcePreference", resourcePreference);
            return map;
        }
    }
}

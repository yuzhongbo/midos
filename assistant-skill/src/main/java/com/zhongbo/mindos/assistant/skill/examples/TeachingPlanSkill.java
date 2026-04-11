package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.skill.examples.util.ChineseNumberParser;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TeachingPlanSkill implements Skill, SkillDescriptorProvider {

    private static final Logger LOGGER = Logger.getLogger(TeachingPlanSkill.class.getName());
    private static final List<String> PLAN_INTENT_TERMS = List.of(
            "教学规划", "教学计划", "学习计划", "复习计划", "课程规划", "学习路线", "提分计划",
            "冲刺计划", "倒排计划", "备考计划", "学习方案", "学习安排",
            "study plan", "teaching plan", "learning plan", "revision plan"
    );
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("\\b(stu-[A-Za-z0-9_-]+)\\b");
    private static final Pattern WEEKLY_HOURS_PATTERN = Pattern.compile(
            "(?:每周|一周|每星期|每个星期|weekly)\\s*([0-9一二两三四五六七八九十百千]+)\\s*(?:个)?小时",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DURATION_WEEKS_PATTERN = Pattern.compile(
            "(?<!每)([0-9一二两三四五六七八九十百千]+)\\s*(?:周|星期)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GOAL_PATTERN = Pattern.compile(
            "(?:目标(?:是|为)?|希望|想要|争取|目标定为)\\s*[:：]?\\s*([^，。；;\\n]+)"
    );
    private static final Map<String, String> TOPIC_ALIASES = Map.ofEntries(
            Map.entry("数学", "数学"),
            Map.entry("math", "math"),
            Map.entry("英语", "英语"),
            Map.entry("english", "english"),
            Map.entry("语文", "语文"),
            Map.entry("chinese", "chinese"),
            Map.entry("物理", "物理"),
            Map.entry("physics", "physics"),
            Map.entry("化学", "化学"),
            Map.entry("chemistry", "chemistry"),
            Map.entry("生物", "生物"),
            Map.entry("biology", "biology"),
            Map.entry("历史", "历史"),
            Map.entry("history", "history"),
            Map.entry("地理", "地理"),
            Map.entry("geography", "geography"),
            Map.entry("政治", "政治"),
            Map.entry("java", "Java"),
            Map.entry("python", "Python")
    );

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
        return "根据目标、周期、薄弱点和学习风格生成个性化教学/学习计划。";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), PLAN_INTENT_TERMS);
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
        String studentId = firstNonBlank(
                asString(attributes, "studentId"),
                firstNonBlank(extractStudentId(context.input()), context.userId())
        );
        String topic = firstNonBlank(asString(attributes, "topic"), inferTopicFromInput(context.input()));
        String goal = firstNonBlank(asString(attributes, "goal"), inferGoalFromInput(context.input()));
        int durationWeeks = clamp(
                firstPositive(
                        parseFlexible(attributes.get("durationWeeks")),
                        parseFlexible(extractDurationWeeksText(context.input())),
                        8
                ),
                1,
                52
        );
        int weeklyHours = clamp(
                firstPositive(
                        parseFlexible(attributes.get("weeklyHours")),
                        parseFlexible(extractWeeklyHoursText(context.input())),
                        6
                ),
                1,
                80
        );

        return new PlanRequest(
                studentId,
                topic,
                firstNonBlank(asString(attributes, "gradeOrLevel"), "未指定"),
                goal,
                durationWeeks,
                weeklyHours,
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
        output.append("我先按你给的信息，整理了一版可直接执行的学习规划。\n");
        output.append("这次会以 ")
                .append(request.topic())
                .append(" 为主线，目标是 ")
                .append(request.goal())
                .append("，周期 ")
                .append(request.durationWeeks())
                .append(" 周，每周大约 ")
                .append(request.weeklyHours())
                .append(" 小时。\n");
        output.append("如果我理解有偏差，你直接改学生画像字段我就能同步重排。\n\n");

        output.append("学生画像小结：学生ID ").append(request.studentId())
                .append("，对象 ").append(request.gradeOrLevel())
                .append("，薄弱点 ").append(joinOrDefault(request.weakTopics()))
                .append("，优势项 ").append(joinOrDefault(request.strongTopics()))
                .append("，学习风格 ").append(joinOrDefault(request.learningStyle()))
                .append("，约束 ").append(joinOrDefault(request.constraints()))
                .append("。\n\n");

        output.append("整体策略：").append(String.valueOf(plan.getOrDefault("summary", ""))).append("\n\n");
        output.append("分阶段安排（先看全局节奏）：\n");
        for (Map<String, Object> phase : asMapList(plan.get("coursePath"))) {
            output.append("- ").append(stringValue(phase.get("phase"), "阶段"))
                    .append(" | 重点: ").append(joinFlexible(phase.get("focus")))
                    .append(" | 每周: ").append(numberValue(phase.get("weeklyHours"), request.weeklyHours())).append(" 小时")
                    .append(" | 里程碑: ").append(joinFlexible(phase.get("milestones")))
                    .append('\n');
        }

        output.append("\n近期每周建议（先给你前几周抓手）：\n");
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
        output.append("\n我们怎么判断这套计划是否有效：KPI=").append(joinFlexible(assessment.get("kpi")))
                .append("；检查点=").append(joinFlexible(assessment.get("checkpoints"))).append("。\n");
        output.append("风险提醒：").append(joinRiskAlerts(plan.get("riskAlerts"))).append("。\n");
        output.append("调整规则：").append(joinFlexible(plan.get("adjustmentRules"))).append("。\n\n");
        output.append("如果你愿意，我下一条可以继续给你：\n");
        output.append("1) 按天拆到可打卡任务；\n");
        output.append("2) 按考试日期倒排冲刺版；\n");
        output.append("3) 按薄弱点生成专项练习清单。\n");
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
        String normalized = normalize(input);
        for (Map.Entry<String, String> entry : TOPIC_ALIASES.entrySet()) {
            if (normalized.contains(normalize(entry.getKey()))) {
                return entry.getValue();
            }
        }
        String cleaned = input;
        for (String term : PLAN_INTENT_TERMS) {
            cleaned = cleaned.replace(term, " ");
        }
        cleaned = STUDENT_ID_PATTERN.matcher(cleaned).replaceAll(" ");
        String weeklyHoursText = extractWeeklyHoursText(cleaned);
        if (!weeklyHoursText.isBlank()) {
            cleaned = cleaned.replace(weeklyHoursText, " ");
        }
        String durationWeeksText = extractDurationWeeksText(cleaned);
        if (!durationWeeksText.isBlank()) {
            cleaned = cleaned.replace(durationWeeksText, " ");
        }
        String goal = inferGoalFromInput(cleaned);
        if (!goal.equals("夯实基础并稳定提升")) {
            cleaned = cleaned.replace(goal, " ");
        }
        cleaned = cleaned.replaceAll("(?:请|帮我|给我|做一个|制定|安排|生成|提供|一份|一个|关于|针对|学生|为|做|的)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? "通用能力提升" : cleaned;
    }

    private String inferGoalFromInput(String input) {
        if (input == null || input.isBlank()) {
            return "夯实基础并稳定提升";
        }
        Matcher matcher = GOAL_PATTERN.matcher(input);
        if (matcher.find()) {
            String goal = matcher.group(1) == null ? "" : matcher.group(1).trim();
            return goal.isBlank() ? "夯实基础并稳定提升" : goal;
        }
        return "夯实基础并稳定提升";
    }

    private String extractStudentId(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        Matcher matcher = STUDENT_ID_PATTERN.matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractDurationWeeksText(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        Matcher matcher = DURATION_WEEKS_PATTERN.matcher(input);
        while (matcher.find()) {
            int start = matcher.start();
            String prefix = input.substring(Math.max(0, start - 2), start);
            if (prefix.endsWith("每")) {
                continue;
            }
            return matcher.group(1);
        }
        return "";
    }

    private String extractWeeklyHoursText(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        Matcher matcher = WEEKLY_HOURS_PATTERN.matcher(input);
        return matcher.find() ? matcher.group(1) : "";
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

    private int parseFlexibleOrDefault(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        String s = String.valueOf(value).trim();
        if (s.isBlank()) return defaultValue;
        Integer parsed = ChineseNumberParser.parseFlexibleNumber(s);
        return parsed == null ? defaultValue : parsed;
    }

    private Integer parseFlexible(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank()) {
            return null;
        }
        return ChineseNumberParser.parseFlexibleNumber(s);
    }

    private int firstPositive(Integer... candidates) {
        if (candidates == null) {
            return 0;
        }
        for (Integer candidate : candidates) {
            if (candidate != null && candidate > 0) {
                return candidate;
            }
        }
        return 0;
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

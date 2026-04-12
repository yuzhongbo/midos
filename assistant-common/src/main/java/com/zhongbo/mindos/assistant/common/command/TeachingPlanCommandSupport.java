package com.zhongbo.mindos.assistant.common.command;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TeachingPlanCommandSupport {

    private static final List<String> PLAN_INTENT_TERMS = List.of(
            "教学规划", "教学计划", "学习计划", "复习计划", "课程规划", "学习路线", "提分计划",
            "冲刺计划", "倒排计划", "备考计划", "学习方案", "学习安排",
            "study plan", "teaching plan", "learning plan", "revision plan"
    );
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("\\b(stu-[A-Za-z0-9_-]+)\\b");
    private static final Pattern TOPIC_BEFORE_PLAN_PATTERN = Pattern.compile("([\\p{L}A-Za-z0-9+#._-]{2,32})\\s*(?:教学规划|教学计划|学习计划|复习计划|课程规划|学习方案|提分计划)");
    private static final Pattern TOPIC_AFTER_VERB_PATTERN = Pattern.compile("(?:学|学习|复习|备考|课程|主线)\\s*([\\p{L}A-Za-z0-9+#._-]{2,32})");
    private static final Pattern WEEKLY_HOURS_PATTERN = Pattern.compile(
            "(?:每周|一周|每星期|每个星期|weekly)\\s*([0-9一二两三四五六七八九十百千万]+)\\s*(?:个)?小时",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DURATION_WEEKS_PATTERN = Pattern.compile(
            "(?<!每)([0-9一二两三四五六七八九十百千万]+)\\s*(?:周|星期)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GOAL_PATTERN = Pattern.compile("(?:目标(?:是|为)?|希望|想要|争取|目标定为)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern LEVEL_PATTERN = Pattern.compile("(?:年级|阶段|level|级别)\\s*[:：]?\\s*([A-Za-z0-9一二三四五六七八九十高初大研Gg-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAK_TOPICS_PATTERN = Pattern.compile("(?:薄弱点|薄弱科目|弱项)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern STRONG_TOPICS_PATTERN = Pattern.compile("(?:优势项|擅长|强项)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern LEARNING_STYLE_PATTERN = Pattern.compile("(?:学习风格|学习方式)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern CONSTRAINTS_PATTERN = Pattern.compile("(?:约束|限制|不可用时段)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("(?:资源偏好|资源|教材偏好)\\s*[:：]?\\s*([^，。；;\\n]+)");
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

    private TeachingPlanCommandSupport() {
    }

    public static Map<String, Object> extractPayload(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "studentId", extractByPattern(input, STUDENT_ID_PATTERN));
        putIfPresent(payload, "topic", inferTopic(input));
        putIfPresent(payload, "goal", extractByPattern(input, GOAL_PATTERN));

        Integer durationWeeks = extractFlexibleNumber(input, DURATION_WEEKS_PATTERN);
        if (durationWeeks != null && durationWeeks > 0) {
            payload.put("durationWeeks", durationWeeks);
        }
        Integer weeklyHours = extractFlexibleNumber(input, WEEKLY_HOURS_PATTERN);
        if (weeklyHours != null && weeklyHours > 0) {
            payload.put("weeklyHours", weeklyHours);
        }

        putIfPresent(payload, "gradeOrLevel", extractByPattern(input, LEVEL_PATTERN));
        putListIfPresent(payload, "weakTopics", extractDelimitedValues(input, WEAK_TOPICS_PATTERN));
        putListIfPresent(payload, "strongTopics", extractDelimitedValues(input, STRONG_TOPICS_PATTERN));
        putListIfPresent(payload, "learningStyle", extractDelimitedValues(input, LEARNING_STYLE_PATTERN));
        putListIfPresent(payload, "constraints", extractDelimitedValues(input, CONSTRAINTS_PATTERN));
        putListIfPresent(payload, "resourcePreference", extractDelimitedValues(input, RESOURCE_PATTERN));
        return Map.copyOf(payload);
    }

    public static Map<String, Object> resolveAttributes(SkillContext context) {
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        Map<String, Object> inferred = extractPayload(context == null ? "" : context.input());
        Map<String, Object> resolved = new LinkedHashMap<>();
        putIfPresent(resolved, "studentId", firstNonBlank(asString(attributes.get("studentId")), asString(inferred.get("studentId")), context == null ? "" : context.userId()));
        putIfPresent(resolved, "topic", firstNonBlank(asString(attributes.get("topic")), asString(inferred.get("topic")), "通用能力提升"));
        putIfPresent(resolved, "goal", firstNonBlank(asString(attributes.get("goal")), asString(inferred.get("goal")), "夯实基础并稳定提升"));
        resolved.put("durationWeeks", clamp(firstPositive(parseFlexibleNumber(attributes.get("durationWeeks")), parseFlexibleNumber(inferred.get("durationWeeks")), 8), 1, 52));
        resolved.put("weeklyHours", clamp(firstPositive(parseFlexibleNumber(attributes.get("weeklyHours")), parseFlexibleNumber(inferred.get("weeklyHours")), 6), 1, 80));
        putIfPresent(resolved, "gradeOrLevel", firstNonBlank(asString(attributes.get("gradeOrLevel")), asString(inferred.get("gradeOrLevel")), "未指定"));
        putListIfPresent(resolved, "weakTopics", asStringList(attributes.containsKey("weakTopics") ? attributes.get("weakTopics") : inferred.get("weakTopics")));
        putListIfPresent(resolved, "strongTopics", asStringList(attributes.containsKey("strongTopics") ? attributes.get("strongTopics") : inferred.get("strongTopics")));
        putListIfPresent(resolved, "learningStyle", asStringList(attributes.containsKey("learningStyle") ? attributes.get("learningStyle") : inferred.get("learningStyle")));
        putListIfPresent(resolved, "constraints", asStringList(attributes.containsKey("constraints") ? attributes.get("constraints") : inferred.get("constraints")));
        putListIfPresent(resolved, "resourcePreference", asStringList(attributes.containsKey("resourcePreference") ? attributes.get("resourcePreference") : inferred.get("resourcePreference")));
        return Map.copyOf(resolved);
    }

    private static String inferTopic(String input) {
        String normalized = normalize(input);
        for (Map.Entry<String, String> entry : TOPIC_ALIASES.entrySet()) {
            if (normalized.contains(normalize(entry.getKey()))) {
                return entry.getValue();
            }
        }
        String explicitTopic = extractTopic(input);
        if (explicitTopic != null && !explicitTopic.isBlank()) {
            return explicitTopic;
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
        String goal = extractByPattern(cleaned, GOAL_PATTERN);
        if (goal != null && !goal.isBlank()) {
            cleaned = cleaned.replace(goal, " ");
        }
        cleaned = cleaned.replaceAll("(?:请|帮我|给我|做一个|制定|安排|生成|提供|一份|一个|关于|针对|学生|为|做|的)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.contains("目标") || cleaned.contains("每周") || cleaned.contains("小时")) {
            return "";
        }
        return cleaned.isBlank() ? "" : cleaned;
    }

    private static String extractTopic(String input) {
        Matcher beforePlanMatcher = TOPIC_BEFORE_PLAN_PATTERN.matcher(input);
        if (beforePlanMatcher.find()) {
            return sanitizeTopic(beforePlanMatcher.group(1));
        }
        Matcher afterVerbMatcher = TOPIC_AFTER_VERB_PATTERN.matcher(input);
        if (afterVerbMatcher.find()) {
            return sanitizeTopic(afterVerbMatcher.group(1));
        }
        return null;
    }

    private static String sanitizeTopic(String rawTopic) {
        if (rawTopic == null || rawTopic.isBlank()) {
            return null;
        }
        return rawTopic.trim()
                .replaceFirst("^(给我一个|给我一份|给我|帮我做|帮我|请帮我|请|做个|做一份)", "")
                .trim();
    }

    private static String extractDurationWeeksText(String input) {
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

    private static String extractWeeklyHoursText(String input) {
        Matcher matcher = WEEKLY_HOURS_PATTERN.matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static Integer extractFlexibleNumber(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        return parseFlexibleNumber(matcher.group(1));
    }

    private static Integer parseFlexibleNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        return parseChineseInteger(normalized);
    }

    private static Integer parseFlexibleNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return parseFlexibleNumber(String.valueOf(value));
    }

    private static Integer parseChineseInteger(String value) {
        String normalized = value.trim().replace('两', '二');
        while (normalized.startsWith("零")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return 0;
        }
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            Integer tens = chineseDigit(normalized.charAt(0));
            return tens == null ? null : tens * 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            Integer ones = chineseDigit(normalized.charAt(1));
            return ones == null ? null : 10 + ones;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            Integer tens = chineseDigit(normalized.charAt(0));
            Integer ones = chineseDigit(normalized.charAt(2));
            return tens == null || ones == null ? null : tens * 10 + ones;
        }
        Integer withWan = parseChineseUnitNumber(normalized, '万', 10_000);
        if (withWan != null) {
            return withWan;
        }
        Integer withThousands = parseChineseUnitNumber(normalized, '千', 1_000);
        if (withThousands != null) {
            return withThousands;
        }
        Integer withHundreds = parseChineseUnitNumber(normalized, '百', 100);
        if (withHundreds != null) {
            return withHundreds;
        }
        return normalized.length() == 1 ? chineseDigit(normalized.charAt(0)) : null;
    }

    private static Integer parseChineseUnitNumber(String normalized, char unitChar, int unitValue) {
        int unitIndex = normalized.indexOf(unitChar);
        if (unitIndex < 0) {
            return null;
        }
        String headPart = normalized.substring(0, unitIndex);
        String tailPart = normalized.substring(unitIndex + 1);
        Integer head = headPart.isBlank() ? 1 : parseChineseInteger(headPart);
        if (head == null) {
            return null;
        }
        if (tailPart.isBlank()) {
            return head * unitValue;
        }
        Integer tail = parseChineseInteger(tailPart);
        return tail == null ? null : head * unitValue + tail;
    }

    private static Integer chineseDigit(char c) {
        return switch (c) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> null;
        };
    }

    private static String extractByPattern(String input, Pattern pattern) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static List<String> extractDelimitedValues(String input, Pattern pattern) {
        String raw = extractByPattern(input, pattern);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,，;；/、]");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static void putListIfPresent(Map<String, Object> payload, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            payload.put(key, values);
        }
    }

    private static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> rawList) {
            List<String> result = new ArrayList<>();
            for (Object item : rawList) {
                String normalized = asString(item);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
            return List.copyOf(result);
        }
        String raw = asString(value);
        if (raw == null) {
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

    private static int firstPositive(Integer... candidates) {
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

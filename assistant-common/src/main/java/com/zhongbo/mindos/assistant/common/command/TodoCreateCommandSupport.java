package com.zhongbo.mindos.assistant.common.command;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TodoCreateCommandSupport {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern TASK_LEADING_PATTERN = Pattern.compile("(?i)^(?:todo(?:\\.create)?|创建待办|新建待办|记个待办|记一下|提醒我|帮我记一下|帮我创建一个待办|安排一下|安排任务)\\s*");
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile("(今天|明天|后天)(上午|中午|下午|晚上)?\\s*([0-9]{1,2})点(半)?");
    private static final Pattern ABSOLUTE_DATE_PATTERN = Pattern.compile("([0-9]{4})[-/年]([0-9]{1,2})[-/月]([0-9]{1,2})(?:日)?");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("([0-9]{1,2})月([0-9]{1,2})日");
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("((?:本周|这周|下周|本|这|下)?)?(?:周|星期)(一|二|三|四|五|六|日|天)");
    private static final Pattern REMINDER_PATTERN = Pattern.compile("(提前\\s*[0-9一二两三四五六七八九十]+\\s*(?:分钟|小时)提醒)");

    private final Clock clock;

    public TodoCreateCommandSupport() {
        this(Clock.systemDefaultZone());
    }

    public TodoCreateCommandSupport(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public Map<String, Object> extractPayload(String input, String timezoneHint) {
        String safeInput = input == null ? "" : input.trim();
        String timezone = firstNonBlank(timezoneHint, inferTimezone(safeInput));
        Map<String, Object> payload = new LinkedHashMap<>();

        String task = inferTask(safeInput);
        if (!task.isBlank()) {
            payload.put("task", task);
        }
        String dueDate = inferDueDate(safeInput, timezone);
        if (!dueDate.isBlank()) {
            payload.put("dueDate", dueDate);
        }
        String priority = inferPriority(safeInput);
        if (!priority.isBlank()) {
            payload.put("priority", priority);
        }
        String reminder = inferReminder(safeInput);
        if (!reminder.isBlank()) {
            payload.put("reminder", reminder);
        }
        if (!timezone.isBlank()) {
            payload.put("timezone", timezone);
        }
        return Map.copyOf(payload);
    }

    public Map<String, Object> resolveAttributes(SkillContext context) {
        Map<String, Object> attributes = context == null || context.attributes() == null ? Map.of() : context.attributes();
        Map<String, Object> inferred = extractPayload(
                context == null || context.input() == null ? "" : context.input(),
                asString(attributes.get("timezone"))
        );
        Map<String, Object> resolved = new LinkedHashMap<>();
        putIfPresent(resolved, "timezone", firstNonBlank(asString(attributes.get("timezone")), asString(inferred.get("timezone"))));
        putIfPresent(resolved, "task", firstNonBlank(asString(attributes.get("task")), asString(inferred.get("task"))));
        putIfPresent(resolved, "dueDate", firstNonBlank(asString(attributes.get("dueDate")), asString(inferred.get("dueDate")), "未指定"));
        putIfPresent(resolved, "priority", firstNonBlank(asString(attributes.get("priority")), asString(inferred.get("priority"))));
        putIfPresent(resolved, "reminder", firstNonBlank(asString(attributes.get("reminder")), asString(inferred.get("reminder"))));
        putIfPresent(resolved, "style", asString(attributes.get("style")));
        return Map.copyOf(resolved);
    }

    private String inferTask(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String candidate = TASK_LEADING_PATTERN.matcher(input.trim()).replaceFirst("");
        candidate = candidate.replaceAll("(?i)(高优先级|低优先级|普通优先级|优先级\\s*[:：=]?\\s*[A-Za-z0-9一二三四五六七八九十]+)", " ");
        candidate = ABSOLUTE_DATE_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = MONTH_DAY_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = WEEKDAY_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = DATE_TIME_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = candidate.replaceAll("(?i)(今天|明天|后天|今晚|明早|明晚|周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日|星期天|下周|本周|这周)", " ");
        candidate = REMINDER_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = candidate.replaceAll("(?i)(截止到?|due|deadline|提醒我|提醒|之前完成|完成)", " ");
        candidate = candidate.replaceAll("[，,。；;]+", " ");
        candidate = candidate.replaceAll("\\s+", " ").trim();
        candidate = candidate.replaceFirst("^[前于把将]\\s*", "");
        return candidate;
    }

    private String inferDueDate(String input, String timezone) {
        if (input == null || input.isBlank()) {
            return "";
        }
        ZoneId zoneId = resolveZoneId(timezone);
        LocalDate today = LocalDate.now(clock.withZone(zoneId));

        Matcher dateTimeMatcher = DATE_TIME_PATTERN.matcher(input);
        if (dateTimeMatcher.find()) {
            LocalDate date = switch (dateTimeMatcher.group(1)) {
                case "今天" -> today;
                case "明天" -> today.plusDays(1);
                case "后天" -> today.plusDays(2);
                default -> today;
            };
            int hour = Integer.parseInt(dateTimeMatcher.group(3));
            if ("下午".equals(dateTimeMatcher.group(2)) || "晚上".equals(dateTimeMatcher.group(2))) {
                hour = hour < 12 ? hour + 12 : hour;
            } else if ("中午".equals(dateTimeMatcher.group(2)) && hour < 11) {
                hour += 12;
            }
            int minute = dateTimeMatcher.group(4) == null ? 0 : 30;
            return DATE_TIME_FORMATTER.format(LocalDateTime.of(date, LocalTime.of(Math.min(hour, 23), minute)));
        }

        Matcher absoluteMatcher = ABSOLUTE_DATE_PATTERN.matcher(input);
        if (absoluteMatcher.find()) {
            return formatDate(Integer.parseInt(absoluteMatcher.group(1)),
                    Integer.parseInt(absoluteMatcher.group(2)),
                    Integer.parseInt(absoluteMatcher.group(3)));
        }

        Matcher monthDayMatcher = MONTH_DAY_PATTERN.matcher(input);
        if (monthDayMatcher.find()) {
            int month = Integer.parseInt(monthDayMatcher.group(1));
            int day = Integer.parseInt(monthDayMatcher.group(2));
            int year = today.getYear();
            LocalDate candidate = safeDate(year, month, day);
            if (candidate != null && candidate.isBefore(today)) {
                candidate = safeDate(year + 1, month, day);
            }
            return candidate == null ? "" : DATE_FORMATTER.format(candidate);
        }

        Matcher weekdayMatcher = WEEKDAY_PATTERN.matcher(input);
        if (weekdayMatcher.find()) {
            String prefix = weekdayMatcher.group(1) == null ? "" : weekdayMatcher.group(1);
            DayOfWeek target = chineseWeekday(weekdayMatcher.group(2));
            if (target != null) {
                LocalDate candidate;
                if ("下周".equals(prefix) || "下".equals(prefix)) {
                    LocalDate nextWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
                    candidate = nextWeekStart.with(TemporalAdjusters.nextOrSame(target));
                } else {
                    candidate = today;
                    while (candidate.getDayOfWeek() != target) {
                        candidate = candidate.plusDays(1);
                    }
                }
                if (candidate.isBefore(today)) {
                    candidate = candidate.plusWeeks(1);
                }
                return DATE_FORMATTER.format(candidate);
            }
        }

        if (input.contains("今天")) {
            return DATE_FORMATTER.format(today);
        }
        if (input.contains("明天")) {
            return DATE_FORMATTER.format(today.plusDays(1));
        }
        if (input.contains("后天")) {
            return DATE_FORMATTER.format(today.plusDays(2));
        }
        if (input.contains("今晚")) {
            return DATE_TIME_FORMATTER.format(LocalDateTime.of(today, LocalTime.of(20, 0)));
        }
        if (input.contains("明早")) {
            return DATE_TIME_FORMATTER.format(LocalDateTime.of(today.plusDays(1), LocalTime.of(9, 0)));
        }
        if (input.contains("明晚")) {
            return DATE_TIME_FORMATTER.format(LocalDateTime.of(today.plusDays(1), LocalTime.of(20, 0)));
        }
        return "";
    }

    private String inferPriority(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (normalized.contains("p0") || normalized.contains("紧急") || normalized.contains("立即") || normalized.contains("马上")) {
            return "紧急";
        }
        if (normalized.contains("高优先级") || normalized.contains("重要") || normalized.contains("尽快")) {
            return "高";
        }
        if (normalized.contains("低优先级") || normalized.contains("不着急")) {
            return "低";
        }
        if (normalized.contains("普通优先级") || normalized.contains("一般")) {
            return "中";
        }
        return "";
    }

    private String inferReminder(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        Matcher matcher = REMINDER_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\s+", "");
        }
        if (input.contains("提醒我") || input.contains("提醒一下")) {
            return "需要提醒";
        }
        return "";
    }

    private String inferTimezone(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        if (normalized.contains("北京时间") || normalized.contains("中国时间")) {
            return "Asia/Shanghai";
        }
        if (normalized.contains("utc")) {
            return "UTC";
        }
        return "";
    }

    private ZoneId resolveZoneId(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return clock.getZone();
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return clock.getZone();
        }
    }

    private String formatDate(int year, int month, int day) {
        LocalDate date = safeDate(year, month, day);
        return date == null ? "" : DATE_FORMATTER.format(date);
    }

    private LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DayOfWeek chineseWeekday(String raw) {
        return switch (raw) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            case "日", "天" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String asString(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }
}

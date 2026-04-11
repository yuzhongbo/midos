package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TodoCreateSkill implements Skill, SkillDescriptorProvider {
    private static final Logger LOGGER = Logger.getLogger(TodoCreateSkill.class.getName());
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern TASK_LEADING_PATTERN = Pattern.compile("(?i)^(?:todo(?:\\.create)?|创建待办|新建待办|记个待办|记一下|提醒我|帮我记一下|帮我创建一个待办|安排一下|安排任务)\\s*");
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile("(今天|明天|后天)(上午|中午|下午|晚上)?\\s*([0-9]{1,2})点(半)?");
    private static final Pattern ABSOLUTE_DATE_PATTERN = Pattern.compile("([0-9]{4})[-/年]([0-9]{1,2})[-/月]([0-9]{1,2})(?:日)?");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("([0-9]{1,2})月([0-9]{1,2})日");
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("((?:本周|这周|下周|本|这|下)?)?(?:周|星期)(一|二|三|四|五|六|日|天)");
    private static final Pattern REMINDER_PATTERN = Pattern.compile("(提前\\s*[0-9一二两三四五六七八九十]+\\s*(?:分钟|小时)提醒)");
    private final LlmClient llmClient;
    private final Clock clock;

    public TodoCreateSkill(LlmClient llmClient) {
        this(llmClient, Clock.systemDefaultZone());
    }

    TodoCreateSkill(LlmClient llmClient, Clock clock) {
        this.llmClient = llmClient;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public TodoCreateSkill() {
        this(null, Clock.systemDefaultZone());
    }

    @Override
    public String name() {
        return "todo.create";
    }

    @Override
    public String description() {
        return "根据任务描述和截止时间生成待办事项，适合快速记任务。";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("待办", "todo", "提醒", "安排任务", "创建任务", "截止", "deadline"));
    }

    @Override
    public SkillResult run(SkillContext context) {
        TodoDraft draft = resolveDraft(context);
        if (draft.task().isBlank()) {
            return SkillResult.failure(name(), "请告诉我要记录的具体待办事项。");
        }
        if (llmClient != null) {
            try {
                StringBuilder prompt = new StringBuilder("你是一个待办事项助手，请根据如下任务和截止日期生成简洁 todo 事项描述，仅输出文本。任务：")
                        .append(draft.task())
                        .append(", 截止日期：")
                        .append(draft.dueDate());
                if (!draft.priority().isBlank()) {
                    prompt.append("。优先级：").append(draft.priority());
                }
                if (!draft.reminder().isBlank()) {
                    prompt.append("。提醒：").append(draft.reminder());
                }
                if (!draft.style().isBlank()) {
                    prompt.append("。执行风格偏好：").append(draft.style());
                }
                if (!draft.timezone().isBlank()) {
                    prompt.append("。时区偏好：").append(draft.timezone());
                }
                String llmReply = llmClient.generateResponse(prompt.toString(), buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for todo.create skill, fallback to local output", ex);
            }
        }
        StringBuilder output = new StringBuilder("好的，我先帮你记下这件事：\n");
        output.append("- 待办：").append(draft.task()).append("\n");
        output.append("- 截止：").append(draft.dueDate()).append("\n");
        if (!draft.priority().isBlank()) {
            output.append("- 优先级：").append(draft.priority()).append("\n");
        }
        if (!draft.reminder().isBlank()) {
            output.append("- 提醒：").append(draft.reminder()).append("\n");
        }
        if (!draft.timezone().isBlank()) {
            output.append("- 时区：").append(draft.timezone()).append("\n");
        }
        output.append("如果你愿意，我可以继续帮你拆成今天/本周的执行步骤。");
        return SkillResult.success(name(), output.toString());
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private TodoDraft resolveDraft(SkillContext context) {
        String input = context == null || context.input() == null ? "" : context.input().trim();
        String timezone = firstNonBlank(asString(context, "timezone", ""), inferTimezone(input));
        String task = firstNonBlank(asString(context, "task", ""), inferTask(input));
        String dueDate = firstNonBlank(asString(context, "dueDate", ""), inferDueDate(input, timezone), "未指定");
        String priority = firstNonBlank(asString(context, "priority", ""), inferPriority(input));
        String reminder = firstNonBlank(asString(context, "reminder", ""), inferReminder(input));
        String style = asString(context, "style", "");
        return new TodoDraft(task, dueDate, priority, reminder, timezone, style);
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

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context == null || context.attributes() == null ? null : context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private record TodoDraft(String task,
                             String dueDate,
                             String priority,
                             String reminder,
                             String timezone,
                             String style) {
    }
}

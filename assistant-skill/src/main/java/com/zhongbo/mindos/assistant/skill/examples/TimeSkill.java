package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class TimeSkill implements Skill {
    private static final Logger LOGGER = Logger.getLogger(TimeSkill.class.getName());
    private final LlmClient llmClient;

    public TimeSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public TimeSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "返回当前服务器时间，可直接问“现在几点了”。";
    }

    @Override
    public List<String> routingKeywords() {
        return List.of("time", "clock", "what time", "几点", "时间", "现在几点了");
    }

    @Override
    public boolean supports(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.toLowerCase();
        return normalized.contains("time")
                || normalized.contains("clock")
                || normalized.contains("几点")
                || normalized.contains("时间")
                || normalized.contains("what time");
    }

    @Override
    public SkillResult run(SkillContext context) {
        if (llmClient != null) {
            try {
                String prompt = "你是一个时间助手，请用自然语言回答当前时间，仅输出文本。";
                String llmReply = llmClient.generateResponse(prompt, buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for time skill, fallback to local output", ex);
            }
        }
        return SkillResult.success(name(), formatCurrentTime(context));
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        if (context.attributes() != null) {
            copyIfPresent(context.attributes(), llmContext, "language");
            copyIfPresent(context.attributes(), llmContext, "timezone");
        }
        return llmContext;
    }

    private String formatCurrentTime(SkillContext context) {
        Locale locale = resolveLocale(context);
        ZoneId zoneId = resolveZoneId(context);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendLocalized(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
                .appendLiteral(' ')
                .appendZoneText(java.time.format.TextStyle.SHORT)
                .toFormatter(locale);
        return localizedPrefix(locale) + formatter.format(now);
    }

    private Locale resolveLocale(SkillContext context) {
        String language = attributeText(context, "language");
        if (language == null) {
            return Locale.getDefault();
        }
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        return locale.getLanguage().isBlank() ? Locale.getDefault() : locale;
    }

    private ZoneId resolveZoneId(SkillContext context) {
        String timezone = attributeText(context, "timezone");
        if (timezone == null) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private String localizedPrefix(Locale locale) {
        String language = locale == null ? "" : locale.getLanguage();
        if (language == null) {
            return "Current time: ";
        }
        return switch (language) {
            case "zh" -> "当前时间：";
            case "ja" -> "現在時刻：";
            case "ko" -> "현재 시간: ";
            case "fr" -> "Heure actuelle : ";
            default -> "Current time: ";
        };
    }

    private String attributeText(SkillContext context, String key) {
        if (context == null || context.attributes() == null) {
            return null;
        }
        Object value = context.attributes().get(key);
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}

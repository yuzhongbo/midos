package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TimeSkill implements Skill, SkillDescriptorProvider {
    public TimeSkill(LlmClient llmClient) {
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
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("time", "clock", "what time", "几点", "时间", "现在几点了"));
    }

    @Override
    public SkillResult run(SkillContext context) {
        return SkillResult.success(name(), formatCurrentTime(context));
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

}

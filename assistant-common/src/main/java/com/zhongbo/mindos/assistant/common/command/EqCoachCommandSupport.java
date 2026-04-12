package com.zhongbo.mindos.assistant.common.command;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class EqCoachCommandSupport {

    private static final Pattern LEADING_NOISE_PATTERN = Pattern.compile("^(?:请|请帮我|帮我|麻烦你|想让你|我想要|给我)(?:用|来)?");

    public Map<String, Object> resolveAttributes(SkillContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        String input = context == null || context.input() == null ? "" : context.input().trim();
        String query = firstNonBlank(attributeText(context, "query"), resolveScenario(input));
        if (!query.isBlank()) {
            resolved.put("query", query);
        }
        String style = firstNonBlank(attributeText(context, "style"), inferStyle(input, query));
        if (!style.isBlank()) {
            resolved.put("style", style);
        }
        String mode = firstNonBlank(attributeText(context, "mode"), inferMode(input));
        if (!mode.isBlank()) {
            resolved.put("mode", mode);
        }
        String priorityFocus = firstNonBlank(attributeText(context, "priorityFocus"), inferPriorityFocus(input));
        if (!priorityFocus.isBlank()) {
            resolved.put("priorityFocus", priorityFocus);
        }
        return Map.copyOf(resolved);
    }

    private String resolveScenario(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String scenario = LEADING_NOISE_PATTERN.matcher(input.trim()).replaceFirst("").trim();
        scenario = scenario.replaceAll("(?i)(高情商|怎么说更好|怎么说|怎么回复|回什么|请分析|分析一下|只分析|只要分析|只要回复|只给我回复|直接版|温和版|职场版|亲密关系版|先看p1|先看p2|先看p3|最重要|先止损|更好)", " ");
        scenario = scenario.replaceAll("\\s*[,，。；;]\\s*", " ");
        scenario = scenario.replaceAll("\\s+", " ").trim();
        return scenario;
    }

    private String inferStyle(String input, String scenario) {
        String normalized = firstNonBlank(input, scenario).toLowerCase(Locale.ROOT);
        if (normalized.contains("直接版") || normalized.contains("直接一点") || normalized.contains("强硬一点")) {
            return "direct";
        }
        if (normalized.contains("职场版") || normalized.contains("老板") || normalized.contains("同事") || normalized.contains("上级")) {
            return "workplace";
        }
        if (normalized.contains("亲密关系") || normalized.contains("伴侣") || normalized.contains("男朋友") || normalized.contains("女朋友") || normalized.contains("老公") || normalized.contains("老婆")) {
            return "intimate";
        }
        if (normalized.contains("温和") || normalized.contains("委婉")) {
            return "gentle";
        }
        return "";
    }

    private String inferMode(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        boolean wantsAnalysis = normalized.contains("分析") || normalized.contains("心理分析");
        boolean wantsReply = normalized.contains("怎么回复")
                || normalized.contains("回什么")
                || normalized.contains("只要话术")
                || normalized.contains("只看话术")
                || normalized.contains("回复模板")
                || normalized.contains("直接发");
        if (normalized.contains("只分析") || (wantsAnalysis && !wantsReply)) {
            return "analysis";
        }
        if (normalized.contains("只要回复") || normalized.contains("只给我回复") || (wantsReply && !wantsAnalysis)) {
            return "reply";
        }
        return "";
    }

    private String inferPriorityFocus(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (normalized.contains("p1") || normalized.contains("最重要") || normalized.contains("先止损")) {
            return "p1";
        }
        if (normalized.contains("p2") || normalized.contains("第二步")) {
            return "p2";
        }
        if (normalized.contains("p3") || normalized.contains("最后再看")) {
            return "p3";
        }
        return "";
    }

    private String attributeText(SkillContext context, String key) {
        if (context == null || context.attributes() == null || key == null) {
            return "";
        }
        Object value = context.attributes().get(key);
        return value == null ? "" : String.valueOf(value).trim();
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
}

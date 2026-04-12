package com.zhongbo.mindos.assistant.common.command;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CodeGenerateCommandSupport {

    public Map<String, Object> resolveAttributes(SkillContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        String task = firstNonBlank(attributeText(context, "task"), sanitizeTask(context == null ? null : context.input()));
        if (!task.isBlank()) {
            resolved.put("task", task);
        }
        String style = attributeText(context, "style");
        if (!style.isBlank()) {
            resolved.put("style", style);
        }
        String language = firstNonBlank(attributeText(context, "language"), attributeText(context, "lang"));
        if (!language.isBlank()) {
            resolved.put("language", language);
        }
        return Map.copyOf(resolved);
    }

    private String sanitizeTask(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.trim()
                .replaceFirst("(?i)^code\\.generate\\s*", "")
                .trim();
    }

    private String attributeText(SkillContext context, String key) {
        if (context == null || context.attributes() == null || !context.attributes().containsKey(key)) {
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

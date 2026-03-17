package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MemoryCompressionPlanningService {

    private static final int MAX_CONDENSED_LINES = 6;
    private static final int MAX_BRIEF_LINES = 3;
    private static final MemoryStyleProfile DEFAULT_STYLE = new MemoryStyleProfile("concise", "direct", "plain");

    private final MemoryConsolidationService memoryConsolidationService;
    private final Map<String, MemoryStyleProfile> styleProfiles = new ConcurrentHashMap<>();

    public MemoryCompressionPlanningService(MemoryConsolidationService memoryConsolidationService) {
        this.memoryConsolidationService = memoryConsolidationService;
    }

    public MemoryStyleProfile updateStyleProfile(String userId, MemoryStyleProfile preferredStyle) {
        String key = normalizeUserId(userId);
        MemoryStyleProfile normalized = normalizeStyle(preferredStyle);
        styleProfiles.put(key, normalized);
        return normalized;
    }

    public MemoryStyleProfile getStyleProfile(String userId) {
        return styleProfiles.getOrDefault(normalizeUserId(userId), DEFAULT_STYLE);
    }

    public MemoryCompressionPlan buildPlan(String userId, String sourceText, MemoryStyleProfile styleOverride) {
        MemoryStyleProfile baseStyle = styleOverride == null
                ? getStyleProfile(userId)
                : mergeStyle(getStyleProfile(userId), styleOverride);

        String raw = memoryConsolidationService.normalizeText(sourceText);
        String condensed = condense(raw);
        String brief = buildBrief(condensed);
        String styled = applyStyle(brief, baseStyle);

        List<MemoryCompressionStep> steps = List.of(
                step("RAW", raw),
                step("CONDENSED", condensed),
                step("BRIEF", brief),
                step("STYLED", styled)
        );
        return new MemoryCompressionPlan(baseStyle, steps, Instant.now());
    }

    private MemoryCompressionStep step(String stage, String content) {
        String safeContent = content == null ? "" : content;
        return new MemoryCompressionStep(stage, safeContent, safeContent.length());
    }

    private MemoryStyleProfile mergeStyle(MemoryStyleProfile current, MemoryStyleProfile override) {
        if (override == null) {
            return current;
        }
        return normalizeStyle(new MemoryStyleProfile(
                isBlank(override.styleName()) ? current.styleName() : override.styleName(),
                isBlank(override.tone()) ? current.tone() : override.tone(),
                isBlank(override.outputFormat()) ? current.outputFormat() : override.outputFormat()
        ));
    }

    private MemoryStyleProfile normalizeStyle(MemoryStyleProfile style) {
        if (style == null) {
            return DEFAULT_STYLE;
        }
        String styleName = normalizeOrDefault(style.styleName(), DEFAULT_STYLE.styleName());
        String tone = normalizeOrDefault(style.tone(), DEFAULT_STYLE.tone());
        String outputFormat = normalizeOrDefault(style.outputFormat(), DEFAULT_STYLE.outputFormat());
        return new MemoryStyleProfile(styleName, tone, outputFormat);
    }

    private String condense(String text) {
        if (isBlank(text)) {
            return "";
        }
        String[] parts = text.split("[\\n。！？!?；;]+");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : parts) {
            String normalized = memoryConsolidationService.normalizeText(part);
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }

        List<String> lines = new ArrayList<>(unique);
        if (lines.size() > MAX_CONDENSED_LINES) {
            lines = lines.subList(0, MAX_CONDENSED_LINES);
        }
        return String.join("\n", lines);
    }

    private String buildBrief(String condensed) {
        if (isBlank(condensed)) {
            return "";
        }
        List<String> lines = condensed.lines()
                .map(memoryConsolidationService::normalizeText)
                .filter(line -> !line.isBlank())
                .limit(MAX_BRIEF_LINES)
                .toList();
        return String.join("; ", lines);
    }

    private String applyStyle(String summary, MemoryStyleProfile style) {
        if (isBlank(summary)) {
            return "";
        }
        String styleName = style.styleName().toLowerCase(Locale.ROOT);
        String tone = style.tone();
        String format = style.outputFormat().toLowerCase(Locale.ROOT);

        String base;
        switch (styleName) {
            case "coach", "teaching" -> base = "建议按步骤执行: " + summary;
            case "action", "todo" -> base = toActionList(summary);
            case "story" -> base = "可以这样理解: " + summary;
            default -> base = summary;
        }

        if ("bullet".equals(format)) {
            base = toBulletList(summary);
        }
        if (!isBlank(tone) && !"direct".equalsIgnoreCase(tone)) {
            base = "[" + tone + "] " + base;
        }
        return base;
    }

    private String toActionList(String summary) {
        List<String> parts = splitSummary(summary);
        if (parts.isEmpty()) {
            return summary;
        }
        StringBuilder builder = new StringBuilder("行动清单: ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(i + 1).append(") ").append(parts.get(i));
        }
        return builder.toString();
    }

    private String toBulletList(String summary) {
        List<String> parts = splitSummary(summary);
        if (parts.isEmpty()) {
            return summary;
        }
        return parts.stream().map(line -> "- " + line).reduce((a, b) -> a + "\n" + b).orElse(summary);
    }

    private List<String> splitSummary(String summary) {
        return List.of(summary.split("[;；\\n]+"))
                .stream()
                .map(memoryConsolidationService::normalizeText)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String normalizeUserId(String userId) {
        return normalizeOrDefault(userId, "local-user").toLowerCase(Locale.ROOT);
    }

    private String normalizeOrDefault(String value, String fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        return memoryConsolidationService.normalizeText(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}


package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String STATE_FILE = "memory-style-profiles.json";
    private static final int MAX_CONDENSED_LINES = 6;
    private static final int MAX_BRIEF_LINES = 3;
    private static final int MAX_KEY_LINES_PREFERRED = 3;
    private static final MemoryStyleProfile DEFAULT_STYLE = new MemoryStyleProfile("concise", "direct", "plain");

    private final MemoryConsolidationService memoryConsolidationService;
    private final Map<String, MemoryStyleProfile> styleProfiles = new ConcurrentHashMap<>();
    private final MemoryStateStore memoryStateStore;

    public MemoryCompressionPlanningService(MemoryConsolidationService memoryConsolidationService) {
        this(memoryConsolidationService, MemoryStateStore.noOp());
    }

    @Autowired
    public MemoryCompressionPlanningService(MemoryConsolidationService memoryConsolidationService,
                                            MemoryStateStore memoryStateStore) {
        this.memoryConsolidationService = memoryConsolidationService;
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        loadState();
    }

    public MemoryStyleProfile updateStyleProfile(String userId, MemoryStyleProfile preferredStyle) {
        return updateStyleProfile(userId, preferredStyle, false, null);
    }

    public MemoryStyleProfile updateStyleProfile(String userId,
                                                 MemoryStyleProfile preferredStyle,
                                                 boolean autoTune,
                                                 String sampleText) {
        String key = normalizeUserId(userId);
        MemoryStyleProfile current = styleProfiles.getOrDefault(key, DEFAULT_STYLE);
        MemoryStyleProfile tuned = autoTune ? mergeStyle(current, inferStyleFromSample(sampleText)) : current;
        MemoryStyleProfile normalized = mergeStyle(tuned, preferredStyle);
        styleProfiles.put(key, normalized);
        persistState();
        return normalized;
    }

    public MemoryStyleProfile getStyleProfile(String userId) {
        return styleProfiles.getOrDefault(normalizeUserId(userId), DEFAULT_STYLE);
    }

    public MemoryCompressionPlan buildPlan(String userId, String sourceText, MemoryStyleProfile styleOverride) {
        return buildPlan(userId, sourceText, styleOverride, null);
    }

    public MemoryCompressionPlan buildPlan(String userId,
                                           String sourceText,
                                           MemoryStyleProfile styleOverride,
                                           String focus) {
        MemoryStyleProfile baseStyle = styleOverride == null
                ? getStyleProfile(userId)
                : mergeStyle(getStyleProfile(userId), styleOverride);

        String raw = memoryConsolidationService.normalizeText(sourceText);
        String condensed = condense(raw);
        String brief = buildBrief(condensed);
        String styled = applyStyle(brief, baseStyle, focus);

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
        String styleName = normalizeStyleName(normalizeOrDefault(style.styleName(), DEFAULT_STYLE.styleName()));
        String tone = normalizeOrDefault(style.tone(), DEFAULT_STYLE.tone());
        String outputFormat = normalizeOutputFormat(normalizeOrDefault(style.outputFormat(), DEFAULT_STYLE.outputFormat()));
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

        List<String> lines = selectImportantLines(new ArrayList<>(unique), MAX_CONDENSED_LINES);
        return String.join("\n", lines);
    }

    private String buildBrief(String condensed) {
        if (isBlank(condensed)) {
            return "";
        }
        List<String> lines = condensed.lines()
                .map(memoryConsolidationService::normalizeText)
                .filter(line -> !line.isBlank())
                .toList();
        lines = selectImportantLines(lines, MAX_BRIEF_LINES);
        return String.join("; ", lines);
    }

    private List<String> selectImportantLines(List<String> lines, int maxLines) {
        if (lines.isEmpty() || maxLines <= 0) {
            return List.of();
        }
        if (lines.size() <= maxLines) {
            return lines;
        }

        List<String> selected = new ArrayList<>(maxLines);
        int keyLimit = Math.min(MAX_KEY_LINES_PREFERRED, maxLines);
        for (int i = lines.size() - 1; i >= 0 && selected.size() < keyLimit; i--) {
            String line = lines.get(i);
            if (memoryConsolidationService.containsKeySignal(line) && !selected.contains(line)) {
                selected.add(0, line);
            }
        }
        List<String> recentTail = new ArrayList<>(maxLines);
        for (int i = lines.size() - 1; i >= 0 && selected.size() + recentTail.size() < maxLines; i--) {
            String line = lines.get(i);
            if (!selected.contains(line) && !recentTail.contains(line)) {
                recentTail.add(0, line);
            }
        }
        selected.addAll(recentTail);
        return selected;
    }

    private String applyStyle(String summary, MemoryStyleProfile style, String focus) {
        if (isBlank(summary)) {
            return "";
        }
        String styleName = normalizeStyleName(style.styleName());
        String tone = style.tone();
        String format = normalizeOutputFormat(style.outputFormat());

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
        return applyFocus(base, focus);
    }

    private String applyFocus(String text, String focus) {
        String normalizedFocus = normalizeFocus(focus);
        if (isBlank(normalizedFocus) || text.isBlank()) {
            return text;
        }
        return switch (normalizedFocus) {
            case "learning" -> "[学习聚焦] " + text;
            case "task" -> "[任务聚焦] " + text;
            case "review" -> "[复盘聚焦] " + text;
            default -> text;
        };
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

    private String normalizeStyleName(String styleName) {
        String normalized = normalizeOrDefault(styleName, DEFAULT_STYLE.styleName()).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "teaching", "teacher", "coach", "教学", "教练" -> "coach";
            case "todo", "action", "行动", "清单" -> "action";
            case "narrative", "story", "故事" -> "story";
            default -> "concise";
        };
    }

    private String normalizeOutputFormat(String outputFormat) {
        String normalized = normalizeOrDefault(outputFormat, DEFAULT_STYLE.outputFormat()).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "bullet", "list", "列表", "清单" -> "bullet";
            default -> "plain";
        };
    }

    private MemoryStyleProfile inferStyleFromSample(String sampleText) {
        String normalized = normalizeOrDefault(sampleText, "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return DEFAULT_STYLE;
        }
        String styleName = "concise";
        if (containsAny(normalized, "任务", "todo", "待办", "行动", "步骤")) {
            styleName = "action";
        } else if (containsAny(normalized, "学习", "教学", "复习", "计划", "讲解")) {
            styleName = "coach";
        }
        String tone = containsAny(normalized, "请", "谢谢", "麻烦") ? "warm" : "direct";
        String outputFormat = normalized.contains("\n") || containsAny(normalized, "1.", "2.", "- ")
                ? "bullet"
                : "plain";
        return new MemoryStyleProfile(styleName, tone, outputFormat);
    }

    private String normalizeFocus(String focus) {
        if (isBlank(focus)) {
            return "";
        }
        String normalized = focus.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "学习", "learning", "study" -> "learning";
            case "任务", "task", "todo" -> "task";
            case "复盘", "review", "总结" -> "review";
            default -> "";
        };
    }

    private boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
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

    private void loadState() {
        Map<String, MemoryStyleProfile> persisted = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                Map::of
        );
        persisted.forEach((userId, profile) -> {
            if (userId != null && profile != null) {
                styleProfiles.put(userId, normalizeStyle(profile));
            }
        });
    }

    private void persistState() {
        memoryStateStore.writeState(STATE_FILE, Map.copyOf(styleProfiles));
    }
}

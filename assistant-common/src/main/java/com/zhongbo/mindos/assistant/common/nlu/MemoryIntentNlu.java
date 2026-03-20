package com.zhongbo.mindos.assistant.common.nlu;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryIntentNlu {

    private static final Pattern STYLE_NAME_PATTERN = Pattern.compile("(?:记忆风格|压缩风格|风格)\\s*(?:改成|改为|设为|设置为|用|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TONE_PATTERN = Pattern.compile("(?:语气|tone)\\s*(?:改成|改为|设为|设置为|用|是|为|=|:)?\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_OUTPUT_PATTERN = Pattern.compile("(?:格式|输出格式|output format)\\s*(?:改成|改为|设为|设置为|用|是|为|=|:)?\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);

    private static final String PROP_FOCUS_TASK_TERMS = "mindos.memory.nlu.focus.task-terms";
    private static final String PROP_FOCUS_LEARNING_TERMS = "mindos.memory.nlu.focus.learning-terms";
    private static final String PROP_FOCUS_REVIEW_TERMS = "mindos.memory.nlu.focus.review-terms";
    private static final String PROP_STYLE_ACTION_TERMS = "mindos.memory.nlu.style.action-terms";
    private static final String PROP_STYLE_COACH_TERMS = "mindos.memory.nlu.style.coach-terms";
    private static final String PROP_STYLE_STORY_TERMS = "mindos.memory.nlu.style.story-terms";
    private static final String PROP_STYLE_CONCISE_TERMS = "mindos.memory.nlu.style.concise-terms";
    private static final String PROP_TONE_WARM_TERMS = "mindos.memory.nlu.tone.warm-terms";
    private static final String PROP_TONE_DIRECT_TERMS = "mindos.memory.nlu.tone.direct-terms";
    private static final String PROP_TONE_NEUTRAL_TERMS = "mindos.memory.nlu.tone.neutral-terms";
    private static final String PROP_FORMAT_BULLET_TERMS = "mindos.memory.nlu.format.bullet-terms";
    private static final String PROP_FORMAT_PLAIN_TERMS = "mindos.memory.nlu.format.plain-terms";

    private MemoryIntentNlu() {
    }

    public static boolean isStyleShowIntent(String input) {
        String normalized = normalize(input);
        return containsAny(normalized, "查看我的记忆风格", "查看记忆风格", "当前记忆风格", "memory style");
    }

    public static boolean isAffirmativeIntent(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return false;
        }
        return Set.of("要", "好的", "好", "行", "可以", "ok", "yes", "确认").contains(normalized);
    }

    public static String extractAutoTuneSample(String input) {
        String normalized = normalize(input);
        if (!containsAny(normalized, "自动微调记忆风格", "根据这段话微调记忆风格", "自动调整记忆风格")) {
            return null;
        }
        String raw = input == null ? "" : input.trim();
        int separator = Math.max(raw.indexOf('：'), raw.indexOf(':'));
        if (separator >= 0 && separator + 1 < raw.length()) {
            String sample = raw.substring(separator + 1).trim();
            return sample.isBlank() ? null : sample;
        }
        return null;
    }

    public static StyleUpdateIntent extractStyleUpdateIntent(String input) {
        String normalized = normalize(input);
        boolean looksLikeUpdate = containsAny(normalized,
                "把记忆风格改成",
                "记忆风格改成",
                "设置记忆风格",
                "记忆风格设为",
                "memory style set",
                "style profile");
        if (!looksLikeUpdate) {
            return null;
        }

        String raw = input == null ? "" : input;
        SynonymConfig config = synonymConfig();
        String styleName = normalizeMappedValue(extractByPattern(raw, STYLE_NAME_PATTERN), config.styleAliases());
        String tone = normalizeMappedValue(extractByPattern(raw, STYLE_TONE_PATTERN), config.toneAliases());
        String outputFormat = normalizeMappedValue(extractByPattern(raw, STYLE_OUTPUT_PATTERN), config.formatAliases());
        return new StyleUpdateIntent(styleName, tone, outputFormat);
    }

    public static CompressionIntent extractCompressionIntent(String input) {
        String normalized = normalize(input);
        if (!containsAny(normalized,
                "压缩这段记忆",
                "压缩这段内容",
                "按我的风格压缩",
                "用我的风格压缩",
                "memory compress",
                "compress memory")) {
            return null;
        }

        String source = input == null ? "" : input.trim();
        source = source.replaceFirst("^(请|帮我|麻烦|请你)", "").trim();
        source = source.replaceFirst("^(按我的风格|用我的风格)", "").trim();
        source = source.replaceFirst("^(来)?压缩这段(记忆|内容)[:：]?", "").trim();
        source = source.replaceFirst("^压缩这段(记忆|内容)[:：]?", "").trim();
        String focus = extractCompressionFocus(input, normalized);
        if (focus != null) {
            source = stripTrailingFocusPhrase(source);
        }

        String original = input == null ? "" : input.trim();
        if (source.isBlank() || source.equals(original)) {
            return new CompressionIntent(null, focus);
        }
        return new CompressionIntent(source, focus);
    }

    private static String extractCompressionFocus(String input, String normalized) {
        SynonymConfig config = synonymConfig();
        String source = input == null ? "" : input;
        Matcher matcher = config.focusCnPattern().matcher(source);
        if (matcher.find()) {
            return mapAliasValue(matcher.group(1), config.focusAliases());
        }
        matcher = config.focusCnAltPattern().matcher(source);
        if (matcher.find()) {
            return mapAliasValue(matcher.group(1), config.focusAliases());
        }
        matcher = config.focusEnPattern().matcher(normalized == null ? "" : normalized);
        if (matcher.find()) {
            return mapAliasValue(matcher.group(1), config.focusAliases());
        }
        return null;
    }

    private static String stripTrailingFocusPhrase(String source) {
        SynonymConfig config = synonymConfig();
        String normalized = source == null ? "" : source.trim();
        normalized = config.focusStripCnPattern().matcher(normalized).replaceFirst("").trim();
        normalized = config.focusStripCnAltPattern().matcher(normalized).replaceFirst("").trim();
        normalized = config.focusStripEnPattern().matcher(normalized).replaceFirst("").trim();
        normalized = config.focusStripEnAltPattern().matcher(normalized).replaceFirst("").trim();
        return normalized;
    }

    private static SynonymConfig synonymConfig() {
        Map<String, Set<String>> focus = new LinkedHashMap<>();
        focus.put("task", termsFor(PROP_FOCUS_TASK_TERMS, "task", "任务", "todo", "待办"));
        focus.put("learning", termsFor(PROP_FOCUS_LEARNING_TERMS, "learning", "study", "学习"));
        focus.put("review", termsFor(PROP_FOCUS_REVIEW_TERMS, "review", "复盘", "总结"));

        Map<String, Set<String>> style = new LinkedHashMap<>();
        style.put("action", termsFor(PROP_STYLE_ACTION_TERMS, "action", "todo", "行动", "清单"));
        style.put("coach", termsFor(PROP_STYLE_COACH_TERMS, "coach", "teaching", "teacher", "教学", "教练"));
        style.put("story", termsFor(PROP_STYLE_STORY_TERMS, "story", "narrative", "故事"));
        style.put("concise", termsFor(PROP_STYLE_CONCISE_TERMS, "concise", "简洁", "精简"));

        Map<String, Set<String>> tone = new LinkedHashMap<>();
        tone.put("warm", termsFor(PROP_TONE_WARM_TERMS, "warm", "温和", "友好"));
        tone.put("direct", termsFor(PROP_TONE_DIRECT_TERMS, "direct", "直接", "简练"));
        tone.put("neutral", termsFor(PROP_TONE_NEUTRAL_TERMS, "neutral", "中性"));

        Map<String, Set<String>> format = new LinkedHashMap<>();
        format.put("bullet", termsFor(PROP_FORMAT_BULLET_TERMS, "bullet", "list", "列表", "清单"));
        format.put("plain", termsFor(PROP_FORMAT_PLAIN_TERMS, "plain", "文本", "自然段"));

        Map<String, String> focusAliases = aliasToCanonical(focus);
        String focusAlternation = toAlternation(focusAliases.keySet());
        Pattern focusCnPattern = Pattern.compile("(?:^|[，,;；\\s])(?:按|以|用)?\\s*(" + focusAlternation + ")\\s*聚焦\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern focusCnAltPattern = Pattern.compile("(?:^|[，,;；\\s])聚焦\\s*(?:到)?\\s*(" + focusAlternation + ")\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern focusEnPattern = Pattern.compile("(?:^|[，,;；\\s])focus\\s*[:：]?\\s*(" + focusAlternation + ")\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern focusStripCnPattern = Pattern.compile("[，,;；]?\\s*(?:按|以|用)?\\s*(" + focusAlternation + ")\\s*聚焦$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern focusStripCnAltPattern = Pattern.compile("[，,;；]?\\s*聚焦\\s*(?:到)?\\s*(" + focusAlternation + ")$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern focusStripEnPattern = Pattern.compile("[，,;；]?\\s*focus\\s*[:：]?\\s*(" + focusAlternation + ")$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern focusStripEnAltPattern = Pattern.compile("[，,;；]?\\s*(" + focusAlternation + ")\\s*focus$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new SynonymConfig(
                focusAliases,
                aliasToCanonical(style),
                aliasToCanonical(tone),
                aliasToCanonical(format),
                focusCnPattern,
                focusCnAltPattern,
                focusEnPattern,
                focusStripCnPattern,
                focusStripCnAltPattern,
                focusStripEnPattern,
                focusStripEnAltPattern
        );
    }

    private static Set<String> termsFor(String propertyKey, String... defaults) {
        Set<String> terms = new LinkedHashSet<>();
        for (String value : defaults) {
            addTerm(terms, value);
        }
        for (String value : readConfiguredTerms(propertyKey)) {
            addTerm(terms, value);
        }
        return terms;
    }

    private static Set<String> readConfiguredTerms(String propertyKey) {
        String configured = System.getProperty(propertyKey);
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        String replaced = configured.replace('，', ',').replace('；', ',').replace(';', ',');
        for (String token : replaced.split(",")) {
            addTerm(values, token);
        }
        return values;
    }

    private static void addTerm(Set<String> container, String value) {
        if (value == null) {
            return;
        }
        String normalized = normalizeToken(value);
        if (!normalized.isBlank()) {
            container.add(normalized);
        }
    }

    private static Map<String, String> aliasToCanonical(Map<String, Set<String>> canonicalToTerms) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : canonicalToTerms.entrySet()) {
            String canonical = normalizeToken(entry.getKey());
            aliasMap.put(canonical, canonical);
            for (String term : entry.getValue()) {
                aliasMap.put(normalizeToken(term), canonical);
            }
        }
        return aliasMap;
    }

    private static String toAlternation(Set<String> terms) {
        return terms.stream()
                .filter(term -> !term.isBlank())
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right)
                .orElse("task|learning|review");
    }

    private static String normalizeMappedValue(String value, Map<String, String> aliases) {
        if (value == null) {
            return null;
        }
        String canonical = mapAliasValue(value, aliases);
        return canonical == null ? value.trim() : canonical;
    }

    private static String mapAliasValue(String value, Map<String, String> aliases) {
        if (value == null) {
            return null;
        }
        return aliases.get(normalizeToken(value));
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String extractByPattern(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input == null ? "" : input);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return normalizeToken(input);
    }

    private record SynonymConfig(
            Map<String, String> focusAliases,
            Map<String, String> styleAliases,
            Map<String, String> toneAliases,
            Map<String, String> formatAliases,
            Pattern focusCnPattern,
            Pattern focusCnAltPattern,
            Pattern focusEnPattern,
            Pattern focusStripCnPattern,
            Pattern focusStripCnAltPattern,
            Pattern focusStripEnPattern,
            Pattern focusStripEnAltPattern
    ) {
    }

    public record CompressionIntent(String source, String focus) {
    }

    public record StyleUpdateIntent(String styleName, String tone, String outputFormat) {
        public boolean hasValues() {
            return (styleName != null && !styleName.isBlank())
                    || (tone != null && !tone.isBlank())
                    || (outputFormat != null && !outputFormat.isBlank());
        }
    }
}



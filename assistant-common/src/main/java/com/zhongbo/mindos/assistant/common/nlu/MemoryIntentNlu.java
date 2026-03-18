package com.zhongbo.mindos.assistant.common.nlu;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryIntentNlu {

    private static final Pattern MEMORY_FOCUS_CN_PATTERN = Pattern.compile("(?:按|以|用)?\\s*(任务|学习|复盘)\\s*聚焦");
    private static final Pattern MEMORY_FOCUS_CN_ALT_PATTERN = Pattern.compile("聚焦\\s*(任务|学习|复盘)");
    private static final Pattern MEMORY_FOCUS_EN_PATTERN = Pattern.compile("focus\\s*[:：]?\\s*(task|learning|review)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_NAME_PATTERN = Pattern.compile("(?:记忆风格|压缩风格|风格)\\s*(?:改成|改为|设为|设置为|用|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TONE_PATTERN = Pattern.compile("(?:语气|tone)\\s*(?:改成|改为|设为|设置为|用|是|为|=|:)?\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_OUTPUT_PATTERN = Pattern.compile("(?:格式|输出格式|output format)\\s*(?:改成|改为|设为|设置为|用|是|为|=|:)?\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);

    private MemoryIntentNlu() {
    }

    public static boolean isStyleShowIntent(String input) {
        String normalized = normalize(input);
        return containsAny(normalized, "查看我的记忆风格", "查看记忆风格", "当前记忆风格", "memory style");
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
        String styleName = extractByPattern(raw, STYLE_NAME_PATTERN);
        String tone = extractByPattern(raw, STYLE_TONE_PATTERN);
        String outputFormat = extractByPattern(raw, STYLE_OUTPUT_PATTERN);
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
        String source = input == null ? "" : input;
        Matcher matcher = MEMORY_FOCUS_CN_PATTERN.matcher(source);
        if (matcher.find()) {
            return mapChineseFocus(matcher.group(1));
        }
        matcher = MEMORY_FOCUS_CN_ALT_PATTERN.matcher(source);
        if (matcher.find()) {
            return mapChineseFocus(matcher.group(1));
        }
        matcher = MEMORY_FOCUS_EN_PATTERN.matcher(normalized == null ? "" : normalized);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String mapChineseFocus(String focus) {
        if (focus == null) {
            return null;
        }
        return switch (focus.trim()) {
            case "任务" -> "task";
            case "学习" -> "learning";
            case "复盘" -> "review";
            default -> null;
        };
    }

    private static String stripTrailingFocusPhrase(String source) {
        String normalized = source == null ? "" : source.trim();
        normalized = normalized.replaceFirst("[，,;；]?\\s*按?(任务|学习|复盘)聚焦$", "").trim();
        normalized = normalized.replaceFirst("[，,;；]?\\s*(task|learning|review)\\s*focus$", "").trim();
        return normalized;
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
        return input
                .replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
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



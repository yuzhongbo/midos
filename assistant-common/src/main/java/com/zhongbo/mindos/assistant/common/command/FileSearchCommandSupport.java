package com.zhongbo.mindos.assistant.common.command;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FileSearchCommandSupport {

    private static final Pattern PATH_ATTR_PATTERN = Pattern.compile("(?i)(?:路径|目录|path)\\s*[:：=]\\s*([`\"']?[^\\s,，。；;]+[`\"']?)");
    private static final Pattern KEYWORD_ATTR_PATTERN = Pattern.compile("(?i)(?:关键词|关键字|keyword|包含)\\s*[:：=]\\s*([`\"']?[^,，。；;]+[`\"']?)");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)(?:前|最多|limit\\s*=?)\\s*([0-9一二两三四五六七八九十]+)\\s*(?:个|条|项|份|个文件)?");
    private static final Map<String, String> FILE_TYPE_ALIASES = Map.ofEntries(
            Map.entry("java文件", ".java"),
            Map.entry("java", ".java"),
            Map.entry(".java", ".java"),
            Map.entry("markdown", ".md"),
            Map.entry("md", ".md"),
            Map.entry(".md", ".md"),
            Map.entry("yaml", ".yaml"),
            Map.entry("yml", ".yml"),
            Map.entry(".yaml", ".yaml"),
            Map.entry(".yml", ".yml"),
            Map.entry("json", ".json"),
            Map.entry(".json", ".json"),
            Map.entry("xml", ".xml"),
            Map.entry(".xml", ".xml"),
            Map.entry("properties", ".properties"),
            Map.entry(".properties", ".properties")
    );

    public Map<String, Object> resolveAttributes(SkillContext context) {
        String input = context == null || context.input() == null ? "" : context.input().trim();
        Map<String, Object> resolved = new LinkedHashMap<>();
        String path = normalizePath(firstNonBlank(
                attributeText(context, "path"),
                extract(input, PATH_ATTR_PATTERN),
                inferPathFromInput(input),
                "./"
        ));
        String fileType = normalizeFileType(firstNonBlank(
                attributeText(context, "fileType"),
                attributeText(context, "type"),
                inferFileTypeFromInput(input)
        ));
        String keyword = normalizeKeyword(firstNonBlank(
                attributeText(context, "keyword"),
                extract(input, KEYWORD_ATTR_PATTERN),
                inferKeywordFromInput(input, path, fileType)
        ));
        resolved.put("path", path);
        if (!keyword.isBlank()) {
            resolved.put("keyword", keyword);
        }
        if (!fileType.isBlank()) {
            resolved.put("fileType", fileType);
        }
        resolved.put("limit", parseLimit(firstNonBlank(attributeText(context, "limit"), extract(input, LIMIT_PATTERN))));
        return Map.copyOf(resolved);
    }

    private String inferPathFromInput(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        for (String token : tokenizeInput(input)) {
            String candidate = stripQuotes(token);
            if (candidate.startsWith("./") || candidate.startsWith("/") || candidate.contains("/")) {
                return candidate;
            }
        }
        return "";
    }

    private String inferFileTypeFromInput(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : FILE_TYPE_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    private String inferKeywordFromInput(String input, String path, String fileType) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String candidate = input;
        candidate = candidate.replaceFirst("(?i)^file\\.search", "").trim();
        candidate = candidate.replaceAll("(?i)(找文件|查文件|搜索文件|search file|grep|帮我|请帮我|看看|找一下)", " ");
        candidate = candidate.replaceAll("(?i)(路径|目录|path|关键词|关键字|keyword|文件|文件名|包含|搜索|查找)\\s*[:：=]?\\s*", " ");
        if (path != null && !path.isBlank()) {
            candidate = candidate.replace(path, " ");
        }
        if (fileType != null && !fileType.isBlank()) {
            candidate = candidate.replace(fileType, " ");
        }
        for (String alias : FILE_TYPE_ALIASES.keySet()) {
            candidate = candidate.replace(alias, " ");
        }
        candidate = LIMIT_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = candidate.replaceAll("[`\"']", " ");
        candidate = candidate.replaceAll("[，,。；;]+", " ");
        candidate = candidate.replaceAll("(?i)(在|下|里|目录下|路径下|相关的|相关|有关的|有关|一下|帮忙|帮我|请帮我|找|查|搜索|前几个|前几项|前几条|个文件|个)", " ");
        candidate = candidate.replaceAll("\\s+", " ").trim();
        return candidate;
    }

    private List<String> tokenizeInput(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : input.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "./";
        }
        String normalized = stripQuotes(path.trim());
        return normalized.isBlank() ? "./" : normalized;
    }

    private String normalizeFileType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (FILE_TYPE_ALIASES.containsKey(normalized)) {
            return FILE_TYPE_ALIASES.get(normalized);
        }
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        return stripQuotes(keyword.trim())
                .replaceAll("^(?:里|下|中)\\s*", "")
                .replaceAll("\\s+", " ");
    }

    private int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return 3;
        }
        String normalized = raw.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Math.max(1, Math.min(8, Integer.parseInt(normalized)));
        }
        Integer parsedChinese = parseSimpleChineseNumber(normalized);
        if (parsedChinese == null) {
            return 3;
        }
        return Math.max(1, Math.min(8, parsedChinese));
    }

    private Integer parseSimpleChineseNumber(String value) {
        String normalized = value.trim().replace('两', '二');
        if (normalized.isBlank()) {
            return null;
        }
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.length() == 1) {
            return switch (normalized.charAt(0)) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> null;
            };
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            Integer ones = parseSimpleChineseNumber(normalized.substring(1));
            return ones == null ? null : 10 + ones;
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            Integer tens = parseSimpleChineseNumber(normalized.substring(0, 1));
            return tens == null ? null : tens * 10;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            Integer tens = parseSimpleChineseNumber(normalized.substring(0, 1));
            Integer ones = parseSimpleChineseNumber(normalized.substring(2));
            return tens == null || ones == null ? null : tens * 10 + ones;
        }
        return null;
    }

    private String attributeText(SkillContext context, String key) {
        if (context == null || context.attributes() == null || !context.attributes().containsKey(key)) {
            return "";
        }
        Object raw = context.attributes().get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private String extract(String input, Pattern pattern) {
        if (input == null || input.isBlank()) {
            return "";
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? stripQuotes(matcher.group(1)) : "";
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[`\"']|[`\"']$", "");
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

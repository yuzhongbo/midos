package com.zhongbo.mindos.assistant.common.command;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewsSearchCommandSupport {

    private static final Pattern SOURCE_PATTERN = Pattern.compile("(?i)(?:^|\\s)source\\s*=\\s*([\\p{L}\\p{N}._-]+)(?:\\s|$)");
    private static final Pattern SORT_PATTERN = Pattern.compile("(?i)(?:^|\\s)sort\\s*=\\s*(latest|relevance)(?:\\s|$)");
    private static final Pattern LIMIT_PARAM_PATTERN = Pattern.compile("(?i)(?:^|\\s)limit\\s*=\\s*([0-9一二两三四五六七八九十百千万]+)(?:\\s|$)");
    private static final Pattern COUNT_LIMIT_PATTERN = Pattern.compile("(?:前|看|来|给我看|给我|帮我看)?\\s*([0-9一二两三四五六七八九十百千万]+)\\s*(?:条|篇|个)");
    private static final Pattern PARAMETER_TOKEN_PATTERN = Pattern.compile("(?i)\\b(source|sort|limit)\\s*=\\s*[^\\s]+");
    private static final Pattern SOURCE_TOKEN_PATTERN = Pattern.compile("(?i)(?:36kr|36氪|serper|serpapi)");
    private static final Pattern LEADING_QUERY_NOISE_PATTERN = Pattern.compile("^(?:只看|仅看|只要|仅限|帮我看|给我看|帮我查|给我查|帮我搜|给我搜|查看|查找|看|查|搜|搜索|查询|请|请帮我)?\\s*");
    private static final Pattern LEADING_TOPIC_CONNECTOR_PATTERN = Pattern.compile("^(?:的|关于|有关|针对|围绕)\\s*");
    private static final Pattern RECENCY_NOISE_PATTERN = Pattern.compile("(?:今天的|今日的|今天|今日|最新的|最新|最近的|最近|实时的|实时)");
    private static final Pattern TRAILING_NEWS_NOISE_PATTERN = Pattern.compile("(?:新闻|资讯|消息|头条|热点|热搜)+$");
    private static final Pattern TRAILING_ACTION_NOISE_PATTERN = Pattern.compile(
            "(?:并|顺便)?\\s*(?:总结(?:一下)?|汇总(?:一下)?|梳理(?:一下|下)?|解读(?:一下)?|分析(?:一下)?|概括(?:一下)?|整理(?:一下)?)(?:吧|呀|一下)?$"
    );

    public Map<String, Object> resolveAttributes(SkillContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        String query = resolveQuery(context);
        if (!query.isBlank()) {
            resolved.put("query", query);
        }
        String source = resolveSource(context);
        if (!source.isBlank()) {
            resolved.put("source", source);
        }
        String sort = resolveSort(context);
        if (!sort.isBlank()) {
            resolved.put("sort", sort);
        }
        int limit = resolveLimit(context);
        if (limit > 0) {
            resolved.put("limit", limit);
        }
        return Map.copyOf(resolved);
    }

    public String normalizeQuery(String rawQuery) {
        String candidate = rawQuery == null ? "" : rawQuery.trim();
        if (candidate.isBlank()) {
            return "";
        }
        candidate = candidate.replaceFirst("(?i)^news[_ ]search", "").trim();
        candidate = PARAMETER_TOKEN_PATTERN.matcher(candidate).replaceAll(" ").trim();
        candidate = COUNT_LIMIT_PATTERN.matcher(candidate).replaceAll(" ").trim();
        candidate = candidate.replace("几条", " ").replace("若干条", " ");
        candidate = SOURCE_TOKEN_PATTERN.matcher(candidate).replaceAll(" ").trim();
        candidate = RECENCY_NOISE_PATTERN.matcher(candidate).replaceAll(" ").trim();
        candidate = candidate.replaceAll("(?:最相关|按相关度|相关度|按相关|relevance)", " ");
        candidate = TRAILING_ACTION_NOISE_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = LEADING_QUERY_NOISE_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = LEADING_TOPIC_CONNECTOR_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = TRAILING_NEWS_NOISE_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = LEADING_TOPIC_CONNECTOR_PATTERN.matcher(candidate).replaceFirst("").trim();
        return candidate.replaceAll("\\s+", " ").trim();
    }

    private String resolveQuery(SkillContext context) {
        String query = normalizeQuery(firstNonBlank(attributeText(context, "query"), attributeText(context, "keyword")));
        if (!query.isBlank()) {
            return query;
        }
        String input = context == null || context.input() == null ? "" : context.input().trim();
        return normalizeQuery(input);
    }

    private String resolveSource(SkillContext context) {
        String source = attributeText(context, "source");
        if (!source.isBlank()) {
            return source;
        }
        source = extractFromInput(context, SOURCE_PATTERN);
        if (!source.isBlank()) {
            return source;
        }
        String normalizedInput = normalize(context == null ? null : context.input());
        if (containsAny(normalizedInput, "36kr", "36氪")) {
            return "36kr";
        }
        if (containsAny(normalizedInput, "serper")) {
            return "serper";
        }
        if (containsAny(normalizedInput, "serpapi")) {
            return "serpapi";
        }
        return "";
    }

    private String resolveSort(SkillContext context) {
        String sort = attributeText(context, "sort");
        if (!sort.isBlank()) {
            return sort;
        }
        sort = extractFromInput(context, SORT_PATTERN);
        if (!sort.isBlank()) {
            return sort;
        }
        String normalizedInput = normalize(context == null ? null : context.input());
        if (containsAny(normalizedInput, "相关度", "按相关", "最相关", "relevance")) {
            return "relevance";
        }
        if (containsAny(normalizedInput, "最新", "最近", "latest")) {
            return "latest";
        }
        return "";
    }

    private int resolveLimit(SkillContext context) {
        Integer parsedAttr = parseFlexibleNumber(attributeText(context, "limit"));
        if (parsedAttr != null && parsedAttr > 0) {
            return parsedAttr;
        }
        Integer fromLimitParam = parseFlexibleNumber(extractFromInput(context, LIMIT_PARAM_PATTERN));
        if (fromLimitParam != null && fromLimitParam > 0) {
            return fromLimitParam;
        }
        String input = context == null || context.input() == null ? "" : context.input();
        Matcher countMatcher = COUNT_LIMIT_PATTERN.matcher(input);
        if (countMatcher.find()) {
            Integer fromCount = parseFlexibleNumber(countMatcher.group(1));
            if (fromCount != null && fromCount > 0) {
                return fromCount;
            }
        }
        if (input.contains("几条") || input.contains("若干条")) {
            return 10;
        }
        return 0;
    }

    private String extractFromInput(SkillContext context, Pattern pattern) {
        String input = context == null || context.input() == null ? "" : context.input();
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private String attributeText(SkillContext context, String key) {
        if (context == null || context.attributes() == null || !context.attributes().containsKey(key)) {
            return "";
        }
        Object raw = context.attributes().get(key);
        if (raw instanceof Number number) {
            return String.valueOf(number.intValue());
        }
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private Integer parseFlexibleNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        return parseSimpleChineseNumber(normalized);
    }

    private Integer parseSimpleChineseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('两', '二');
        while (normalized.startsWith("零")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return 0;
        }
        Integer withWan = parseChineseUnitNumber(normalized, '万', 10_000);
        if (withWan != null) {
            return withWan;
        }
        Integer withThousands = parseChineseUnitNumber(normalized, '千', 1000);
        if (withThousands != null) {
            return withThousands;
        }
        Integer withHundreds = parseChineseUnitNumber(normalized, '百', 100);
        if (withHundreds != null) {
            return withHundreds;
        }
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            Integer tens = chineseDigit(normalized.charAt(0));
            return tens == null ? null : tens * 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            Integer ones = chineseDigit(normalized.charAt(1));
            return ones == null ? null : 10 + ones;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            Integer tens = chineseDigit(normalized.charAt(0));
            Integer ones = chineseDigit(normalized.charAt(2));
            return (tens == null || ones == null) ? null : tens * 10 + ones;
        }
        if (normalized.length() == 1) {
            return chineseDigit(normalized.charAt(0));
        }
        return null;
    }

    private Integer parseChineseUnitNumber(String normalized, char unitChar, int unitValue) {
        int index = normalized.indexOf(unitChar);
        if (index < 0) {
            return null;
        }
        String headPart = normalized.substring(0, index);
        String tailPart = normalized.substring(index + 1);
        Integer head = headPart.isBlank() ? 1 : parseSimpleChineseNumber(headPart);
        if (head == null) {
            return null;
        }
        Integer tail = parseSimpleChineseNumber(tailPart);
        if (tail == null) {
            tail = 0;
        }
        return head * unitValue + tail;
    }

    private Integer chineseDigit(char c) {
        return switch (c) {
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

    private boolean containsAny(String text, String... terms) {
        if (text == null || text.isBlank() || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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

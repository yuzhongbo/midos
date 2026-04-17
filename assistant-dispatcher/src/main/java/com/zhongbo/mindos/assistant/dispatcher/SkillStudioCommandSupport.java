package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkillStudioCommandSupport {

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_HINT_PATTERN = Pattern.compile(
            "(?i)(?:叫|命名为|名字(?:叫)?|named?|name(?:d)?\\s+as)\\s*[\"“']?([\\p{L}\\p{N}._-]{2,64})[\"”']?"
    );

    public Map<String, Object> resolveAttributes(SkillContext context) {
        return resolveAttributes(
                context == null ? "" : context.input(),
                context == null || context.attributes() == null ? Map.of() : context.attributes()
        );
    }

    public Map<String, Object> resolveAttributes(String userInput, Map<String, Object> existingAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>(existingAttributes == null ? Map.of() : existingAttributes);
        String request = firstNonBlank(
                stringValue(attributes.get("request")),
                stringValue(attributes.get("input")),
                userInput
        );
        if (!request.isBlank()) {
            attributes.putIfAbsent("request", request);
        }
        String normalized = normalize(request);
        if (attributesMissing(attributes, "action")) {
            putIfPresent(attributes, "action", detectAction(normalized));
        }
        String sourceUrl = firstNonBlank(
                stringValue(attributes.get("sourceUrl")),
                extractUrl(request)
        );
        if (!sourceUrl.isBlank()) {
            attributes.putIfAbsent("sourceUrl", sourceUrl);
        }
        if (attributesMissing(attributes, "sourceType")) {
            putIfPresent(attributes, "sourceType", detectSourceType(normalized, sourceUrl));
        }
        if (attributesMissing(attributes, "goal")) {
            putIfPresent(attributes, "goal", extractGoal(request, normalized));
        }
        if (attributesMissing(attributes, "skillNameHint")) {
            putIfPresent(attributes, "skillNameHint", extractSkillNameHint(request, sourceUrl));
        }
        attributes.putIfAbsent("publishMode", "draft");
        if (attributesMissing(attributes, "riskLevel")) {
            putIfPresent(attributes, "riskLevel", resolveRiskLevel(
                    stringValue(attributes.get("sourceType")),
                    stringValue(attributes.get("action"))
            ));
        }
        return attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }

    private String detectAction(String normalized) {
        if (normalized.isBlank()) {
            return "create";
        }
        if (containsAny(normalized, "下线", "停用", "移除", "删除")) {
            return "retire";
        }
        if (containsAny(normalized, "发布", "上线", "启用", "投入使用")) {
            return "publish";
        }
        if (containsAny(normalized, "测试", "验收", "验证", "试跑")) {
            return "test";
        }
        if (containsAny(normalized, "学习", "借鉴", "吸收", "适配", "封装", "参考")) {
            return "adapt";
        }
        if (containsAny(normalized, "导入", "接入", "加载", "挂载")) {
            return "import";
        }
        if (containsAny(normalized, "查看方案", "看看方案", "分析方案", "检查方案", "inspect")) {
            return "inspect";
        }
        return "create";
    }

    private String detectSourceType(String normalized, String sourceUrl) {
        String normalizedUrl = normalize(sourceUrl);
        if (normalized.isBlank() && normalizedUrl.isBlank()) {
            return "builtin";
        }
        if (normalizedUrl.endsWith(".jar") || containsAny(normalized, " jar ", ".jar", "jar包", "jar 包")) {
            return "jar";
        }
        if (containsAny(normalized, "mcp", "model context protocol")) {
            return "mcp";
        }
        if (containsAny(normalized, "openapi", "swagger")) {
            return "openapi";
        }
        boolean explicitRepoCue = containsAny(normalized, "repo", "仓库", "开源", "源码");
        if (containsAny(normalizedUrl, "github.com", "gitlab.com")
                || explicitRepoCue
                || containsAny(normalized, "github repo", "gitlab repo", "github 仓库", "gitlab 仓库")) {
            return "repo";
        }
        if (containsAny(normalized, "api", "http接口", "http 接口", "rest")) {
            return "cloud-api";
        }
        return sourceUrl == null || sourceUrl.isBlank() ? "builtin" : "external";
    }

    private String extractGoal(String request, String normalized) {
        if (request == null || request.isBlank()) {
            return "";
        }
        String trimmed = stripLeadingRequestPrefix(request);
        for (String marker : List.of("用于", "用来", "负责", "实现", "支持")) {
            int index = trimmed.indexOf(marker);
            if (index >= 0 && index + marker.length() < trimmed.length()) {
                String candidate = trimNoise(trimmed.substring(index + marker.length()));
                if (!candidate.isBlank()) {
                    return capText(candidate, 96);
                }
            }
        }
        String candidate = trimNoise(trimmed
                .replaceFirst("(?i)^(开发|创建|新增|设计|实现|生成|做|写|导入|接入|加载|学习|适配|封装)\\s*", "")
                .replaceFirst("(?i)^(一个|个|一套|一项)\\s*", "")
                .replaceFirst("(?i)^(skill|技能|能力|工具)\\s*", ""));
        if (looksGenericGoal(normalized, candidate)) {
            return "";
        }
        return capText(candidate, 96);
    }

    private String extractSkillNameHint(String request, String sourceUrl) {
        if (request != null && !request.isBlank()) {
            Matcher matcher = NAME_HINT_PATTERN.matcher(request);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            try {
                URI uri = URI.create(sourceUrl.trim());
                String host = uri.getHost() == null ? "" : uri.getHost().trim().toLowerCase(Locale.ROOT);
                if (!host.isBlank()) {
                    String simplified = host.replace("www.", "").replaceAll("[^a-z0-9]+", ".");
                    return simplified.isBlank() ? "" : simplified;
                }
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        return "";
    }

    private String resolveRiskLevel(String sourceType, String action) {
        String normalizedSourceType = normalize(sourceType);
        if (containsAny(normalizedSourceType, "jar", "repo")) {
            return "high";
        }
        if (containsAny(normalizedSourceType, "mcp", "openapi", "cloud-api", "external")) {
            return "medium";
        }
        return "publish".equalsIgnoreCase(action) ? "medium" : "low";
    }

    private String extractUrl(String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        Matcher matcher = URL_PATTERN.matcher(request);
        if (!matcher.find()) {
            return "";
        }
        String candidate = matcher.group(1).trim();
        while (!candidate.isBlank() && isTrailingUrlPunctuation(candidate.charAt(candidate.length() - 1))) {
            candidate = candidate.substring(0, candidate.length() - 1).trim();
        }
        return candidate;
    }

    private boolean looksGenericGoal(String normalizedRequest, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return true;
        }
        String normalizedCandidate = normalize(candidate);
        return normalizedCandidate.equals(normalizedRequest)
                && !containsAny(normalizedCandidate, "用于", "用来", "负责", "实现", "支持")
                && candidate.length() <= 10;
    }

    private String stripLeadingRequestPrefix(String request) {
        String trimmed = request == null ? "" : request.trim();
        return trimmed
                .replaceFirst("^(帮我|请|麻烦|我想|我要|想要|想做|需要)\\s*", "")
                .replaceFirst("^把\\s*", "");
    }

    private String trimNoise(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim()
                .replaceAll("(?i)^(这个|一个|个|一套|一项)\\s*", "")
                .replaceAll("(?i)^(skill|技能|能力|工具)\\s*", "")
                .replaceAll("(?i)(skill|技能|能力|工具)$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return trimmed;
    }

    private void putIfPresent(Map<String, Object> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.putIfAbsent(key, value);
        }
    }

    private boolean attributesMissing(Map<String, Object> attributes, String key) {
        return attributes == null || !attributes.containsKey(key) || stringValue(attributes.get(key)).isBlank();
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String haystack, String... terms) {
        if (haystack == null || haystack.isBlank() || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && haystack.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrailingUrlPunctuation(char ch) {
        return ch == ')' || ch == ']' || ch == '}' || ch == '>' || ch == '"'
                || ch == '.' || ch == ',' || ch == '。' || ch == '；' || ch == ';';
    }

    private String capText(String text, int max) {
        if (text == null || text.isBlank() || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }
}

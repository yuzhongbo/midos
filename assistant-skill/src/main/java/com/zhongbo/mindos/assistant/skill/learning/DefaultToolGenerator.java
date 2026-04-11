package com.zhongbo.mindos.assistant.skill.learning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

@Component
public class DefaultToolGenerator implements ToolGenerator {

    private static final Pattern WEB_TERMS = Pattern.compile("(抓取|爬虫|scrape|crawl|网页|网站|html|http|https|url)", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_PACKAGE = "com.zhongbo.mindos.assistant.skill.generated";

    private final String generatedPackage;

    public DefaultToolGenerator(@Value("${mindos.skills.generated.package:" + DEFAULT_PACKAGE + "}") String generatedPackage) {
        this.generatedPackage = normalizePackage(generatedPackage);
    }

    @Override
    public ToolGenerationResult generate(ToolGenerationRequest request) {
        ToolGenerationRequest safeRequest = request == null
                ? new ToolGenerationRequest("", "", "", Map.of())
                : request;

        ToolGenerationKind kind = detectKind(safeRequest);
        String baseName = resolveBaseName(safeRequest, kind);
        String requestFingerprint = requestFingerprint(safeRequest);
        String skillName = "generated." + baseName + "." + requestFingerprint;
        String className = buildClassName(baseName, requestFingerprint, kind);
        List<String> keywords = buildKeywords(safeRequest, kind, baseName);
        String description = buildDescription(safeRequest, kind);
        String rationale = buildRationale(safeRequest, kind);
        String sourceCode = kind == ToolGenerationKind.WEB_SCRAPER
                ? buildWebScraperSource(skillName, className, description, keywords, safeRequest, requestFingerprint)
                : buildTemplateSource(skillName, className, description, keywords, safeRequest, requestFingerprint);

        return new ToolGenerationResult(
                skillName,
                generatedPackage,
                className,
                kind,
                description,
                keywords,
                sourceCode,
                rationale,
                Map.of(
                        "request", safeRequest.request(),
                        "userId", safeRequest.userId(),
                        "fingerprint", requestFingerprint,
                        "kind", kind.name(),
                        "baseName", baseName
                )
        );
    }

    private ToolGenerationKind detectKind(ToolGenerationRequest request) {
        String normalized = request.normalizedRequest();
        if (WEB_TERMS.matcher(normalized).find()) {
            return ToolGenerationKind.WEB_SCRAPER;
        }
        if (!request.hintText("url").isBlank() || !request.hintText("targetUrl").isBlank()) {
            return ToolGenerationKind.WEB_SCRAPER;
        }
        return ToolGenerationKind.TEMPLATE;
    }

    private String resolveBaseName(ToolGenerationRequest request, ToolGenerationKind kind) {
        String hint = request.skillNameHint();
        if (hint == null || hint.isBlank()) {
            hint = kind == ToolGenerationKind.WEB_SCRAPER ? "web.scrape" : "custom.tool";
        }
        String normalized = hint.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]+", ".")
                .replaceAll("\\.+", ".")
                .replaceAll("^\\.+|\\.+$", "");
        if (normalized.isBlank()) {
            return kind == ToolGenerationKind.WEB_SCRAPER ? "web.scrape" : "custom.tool";
        }
        return normalized;
    }

    private String buildClassName(String baseName, String fingerprint, ToolGenerationKind kind) {
        String readable = toClassSegment(baseName);
        String prefix = kind == ToolGenerationKind.WEB_SCRAPER ? "Web" : "Custom";
        return "Generated" + prefix + readable + "Skill" + fingerprint.toUpperCase(Locale.ROOT);
    }

    private List<String> buildKeywords(ToolGenerationRequest request,
                                       ToolGenerationKind kind,
                                       String baseName) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (kind == ToolGenerationKind.WEB_SCRAPER) {
            keywords.addAll(List.of("抓取", "爬虫", "scrape", "crawl", "网页", "网站", "html", "url"));
        } else {
            keywords.addAll(List.of("generated", "自定义", "auto", "dynamic", baseName));
        }
        addRequestKeywords(keywords, request.request());
        request.hints().forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    addKeyword(keywords, item);
                }
                return;
            }
            addKeyword(keywords, key);
            addKeyword(keywords, value);
        });
        return List.copyOf(keywords);
    }

    private void addRequestKeywords(Set<String> keywords, String requestText) {
        if (requestText == null || requestText.isBlank()) {
            return;
        }
        for (String token : requestText.split("[\\s,，。；;:：/\\\\]+")) {
            addKeyword(keywords, token);
        }
    }

    private void addKeyword(Set<String> keywords, Object rawValue) {
        if (rawValue == null) {
            return;
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return;
        }
        if (text.length() < 2 && !containsHan(text)) {
            return;
        }
        keywords.add(text);
    }

    private boolean containsHan(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String buildDescription(ToolGenerationRequest request, ToolGenerationKind kind) {
        String requestText = request.request().isBlank() ? "用户请求" : request.request();
        if (kind == ToolGenerationKind.WEB_SCRAPER) {
            return "根据 URL 自动抓取网页标题、链接和内容预览，适合“" + requestText + "”。";
        }
        return "根据用户需求自动生成的动态技能模板，适合“" + requestText + "”。";
    }

    private String buildRationale(ToolGenerationRequest request, ToolGenerationKind kind) {
        String requestText = request.request().isBlank() ? "未提供需求" : request.request();
        return "ToolGenerator detected " + kind.name() + " for request: " + requestText;
    }

    private String buildWebScraperSource(String skillName,
                                         String className,
                                         String description,
                                         List<String> keywords,
                                         ToolGenerationRequest request,
                                         String fingerprint) {
        String urlPattern = "https?://[^\\s\\\"'<>]+";
        String titlePattern = "(?is)<title[^>]*>(.*?)</title>";
        String linkPattern = "(?is)<a[^>]+href\\s*=\\s*['\\\"](https?://[^'\\\" >]+)['\\\"]";
        String keywordsLiteral = javaListLiteral(keywords);
        String requestSummary = request.request().isBlank() ? "未提供" : request.request();
        String hintSummary = request.hints().isEmpty() ? "无" : request.hints().toString();

        return """
                package %s;

                import com.zhongbo.mindos.assistant.common.SkillContext;
                import com.zhongbo.mindos.assistant.common.SkillResult;
                import com.zhongbo.mindos.assistant.skill.Skill;

                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;
                import java.nio.charset.StandardCharsets;
                import java.time.Duration;
                import java.util.LinkedHashSet;
                import java.util.List;
                import java.util.Locale;
                import java.util.Map;
                import java.util.regex.Matcher;
                import java.util.regex.Pattern;

                public final class %s implements Skill {
                    private static final Pattern URL_PATTERN = Pattern.compile(%s);
                    private static final Pattern TITLE_PATTERN = Pattern.compile(%s);
                    private static final Pattern LINK_PATTERN = Pattern.compile(%s);
                    private static final List<String> KEYWORDS = %s;

                    @Override
                    public String name() {
                        return %s;
                    }

                    @Override
                    public String description() {
                        return %s;
                    }

                    @Override
                    public List<String> routingKeywords() {
                        return KEYWORDS;
                    }

                    @Override
                    public boolean supports(String input) {
                        if (input == null || input.isBlank()) {
                            return false;
                        }
                        String normalized = input.toLowerCase(Locale.ROOT);
                        if (normalized.contains("http://") || normalized.contains("https://")) {
                            return true;
                        }
                        for (String keyword : KEYWORDS) {
                            if (!keyword.isBlank() && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public SkillResult run(SkillContext context) {
                        String url = resolveUrl(context);
                        if (url.isBlank()) {
                            return SkillResult.failure(name(), "缺少 url 参数或输入中的网址");
                        }
                        try {
                            HttpClient client = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofSeconds(10))
                                    .followRedirects(HttpClient.Redirect.NORMAL)
                                    .build();
                            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(20))
                                    .header("User-Agent", "MindOS-Generated-Skill/1.0")
                                    .GET()
                                    .build();
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                            String body = response.body() == null ? "" : response.body();
                            return SkillResult.success(name(), renderResponse(url, response.statusCode(), body));
                        } catch (Exception ex) {
                            return SkillResult.failure(name(), "网页抓取失败: " + ex.getMessage());
                        }
                    }

                    private String resolveUrl(SkillContext context) {
                        if (context != null && context.attributes() != null) {
                            for (String key : List.of("url", "targetUrl", "website", "site", "endpoint")) {
                                Object value = context.attributes().get(key);
                                String candidate = value == null ? "" : String.valueOf(value).trim();
                                if (!candidate.isBlank()) {
                                    return trimUrl(candidate);
                                }
                            }
                        }
                        String input = context == null || context.input() == null ? "" : context.input();
                        Matcher matcher = URL_PATTERN.matcher(input);
                        if (matcher.find()) {
                            return trimUrl(matcher.group());
                        }
                        return "";
                    }

                    private String renderResponse(String url, int statusCode, String body) {
                        String title = extractTitle(body);
                        List<String> links = extractLinks(body);
                        String text = stripTags(body);
                        return new StringBuilder()
                                .append("网页抓取完成\\n")
                                .append("- Skill: ").append(%s).append("\\n")
                                .append("- URL: ").append(url).append("\\n")
                                .append("- HTTP状态: ").append(statusCode).append("\\n")
                                .append("- 标题: ").append(title.isBlank() ? "未找到" : title).append("\\n")
                                .append("- 链接数: ").append(links.size()).append("\\n")
                                .append("- 链接预览: ").append(links.isEmpty() ? "无" : String.join(", ", links.stream().limit(5).toList())).append("\\n")
                                .append("- 内容预览: ").append(clip(text, 500))
                                .append("\\n- 需求: ").append(%s)
                                .append("\\n- 生成提示: ").append(%s)
                                .toString();
                    }

                    private String extractTitle(String body) {
                        Matcher matcher = TITLE_PATTERN.matcher(body == null ? "" : body);
                        return matcher.find() ? clean(matcher.group(1)) : "";
                    }

                    private List<String> extractLinks(String body) {
                        LinkedHashSet<String> links = new LinkedHashSet<>();
                        Matcher matcher = LINK_PATTERN.matcher(body == null ? "" : body);
                        while (matcher.find()) {
                            String link = trimUrl(matcher.group(1));
                            if (!link.isBlank()) {
                                links.add(link);
                            }
                            if (links.size() >= 10) {
                                break;
                            }
                        }
                        return List.copyOf(links);
                    }

                    private String stripTags(String body) {
                        String text = body == null ? "" : body
                                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                                .replaceAll("<[^>]+>", " ");
                        return clean(text);
                    }

                    private String clean(String text) {
                        return text == null ? "" : text.replaceAll("\\\\s+", " ").trim();
                    }

                    private String trimUrl(String url) {
                        if (url == null) {
                            return "";
                        }
                        String candidate = url.trim();
                        while (!candidate.isBlank() && isTrailingUrlPunctuation(candidate.charAt(candidate.length() - 1))) {
                            candidate = candidate.substring(0, candidate.length() - 1).trim();
                        }
                        return candidate;
                    }

                    private boolean isTrailingUrlPunctuation(char ch) {
                        return ch == ')' || ch == ']' || ch == '}' || ch == '>' || ch == '"'
                                || ch == '.' || ch == ',' || ch == '。' || ch == '；' || ch == ';';
                    }

                    private String clip(String text, int max) {
                        if (text == null || text.isBlank()) {
                            return "";
                        }
                        if (text.length() <= max) {
                            return text;
                        }
                        return text.substring(0, Math.max(0, max - 3)) + "...";
                    }
                }
                """
                .formatted(
                        generatedPackage,
                        className,
                        javaStringLiteral(urlPattern),
                        javaStringLiteral(titlePattern),
                        javaStringLiteral(linkPattern),
                        keywordsLiteral,
                        javaStringLiteral(skillName),
                        javaStringLiteral(description),
                        javaStringLiteral(skillName),
                        javaStringLiteral(requestSummary),
                        javaStringLiteral(hintSummary)
                );
    }

    private String buildTemplateSource(String skillName,
                                       String className,
                                       String description,
                                       List<String> keywords,
                                       ToolGenerationRequest request,
                                       String fingerprint) {
        String keywordsLiteral = javaListLiteral(keywords);
        String requestSummary = request.request().isBlank() ? "未提供" : request.request();
        String hintSummary = request.hints().isEmpty() ? "无" : request.hints().toString();

        return """
                package %s;

                import com.zhongbo.mindos.assistant.common.SkillContext;
                import com.zhongbo.mindos.assistant.common.SkillResult;
                import com.zhongbo.mindos.assistant.skill.Skill;

                import java.util.List;
                import java.util.Locale;

                public final class %s implements Skill {
                    private static final List<String> KEYWORDS = %s;

                    @Override
                    public String name() {
                        return %s;
                    }

                    @Override
                    public String description() {
                        return %s;
                    }

                    @Override
                    public List<String> routingKeywords() {
                        return KEYWORDS;
                    }

                    @Override
                    public boolean supports(String input) {
                        if (input == null || input.isBlank()) {
                            return false;
                        }
                        String normalized = input.toLowerCase(Locale.ROOT);
                        for (String keyword : KEYWORDS) {
                            if (!keyword.isBlank() && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                                return true;
                            }
                        }
                        return normalized.contains(name().toLowerCase(Locale.ROOT));
                    }

                    @Override
                    public SkillResult run(SkillContext context) {
                        String input = context == null || context.input() == null ? "" : context.input().trim();
                        String userId = context == null || context.userId() == null ? "" : context.userId().trim();
                        return SkillResult.success(
                                name(),
                                new StringBuilder()
                                        .append("已生成动态技能模板\\n")
                                        .append("- Skill: ").append(name()).append("\\n")
                                        .append("- 需求: ").append(%s).append("\\n")
                                        .append("- 用户: ").append(userId).append("\\n")
                                        .append("- 输入: ").append(input).append("\\n")
                                        .append("- 提示: ").append(%s).append("\\n")
                                        .append("- Fingerprint: ").append(%s)
                                        .toString()
                        );
                    }
                }
                """
                .formatted(
                        generatedPackage,
                        className,
                        keywordsLiteral,
                        javaStringLiteral(skillName),
                        javaStringLiteral(description),
                        javaStringLiteral(requestSummary),
                        javaStringLiteral(hintSummary),
                        javaStringLiteral(fingerprint)
                );
    }

    private String javaListLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "List.of()";
        }
        StringJoiner joiner = new StringJoiner(", ", "List.of(", ")");
        for (String value : values) {
            joiner.add(javaStringLiteral(value));
        }
        return joiner.toString();
    }

    private String javaStringLiteral(String value) {
        String safe = value == null ? "" : value;
        String escaped = safe
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private String normalizePackage(String value) {
        String normalized = value == null ? DEFAULT_PACKAGE : value.trim();
        if (normalized.isBlank()) {
            return DEFAULT_PACKAGE;
        }
        return normalized.replaceAll("[^\\p{IsHan}\\p{L}\\p{N}.]+", ".").replaceAll("\\.+", ".");
    }

    private String requestFingerprint(ToolGenerationRequest request) {
        String raw = request.userId() + "|" + request.request() + "|" + request.skillNameHint() + "|" + request.hints();
        byte[] hash = sha256(raw);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        String normalized = encoded.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (normalized.length() < 8) {
            normalized = normalized + "mindos";
        }
        return normalized.substring(0, Math.min(8, normalized.length()));
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String toClassSegment(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return "Custom";
        }
        StringBuilder builder = new StringBuilder();
        for (String token : baseName.split("[._-]+")) {
            if (token.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                builder.append(token.substring(1).replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]", ""));
            }
        }
        return builder.length() == 0 ? "Custom" : builder.toString();
    }
}

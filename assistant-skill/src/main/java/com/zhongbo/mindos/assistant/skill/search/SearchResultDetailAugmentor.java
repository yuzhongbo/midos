package com.zhongbo.mindos.assistant.skill.search;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchResultDetailAugmentor {

    private static final Logger LOGGER = Logger.getLogger(SearchResultDetailAugmentor.class.getName());

    private static final int DEFAULT_TIMEOUT_MS = 4500;
    private static final int DEFAULT_MAX_CANDIDATES = 3;
    private static final int DEFAULT_MAX_SUMMARY_CHARS = 320;
    private static final int DEFAULT_MAX_PAGE_CHARS = 6000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final String DEFAULT_USER_AGENT = "MindOS/1.0 (detail-fetch)";

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern LABELED_URL_PATTERN = Pattern.compile("^(?:链接|网址|来源|source|url|link)\\s*[:：]\\s*(https?://\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_MARKER_PATTERN = Pattern.compile("^\\s*(?:\\d+[.)、]|[-*•]|\\[[0-9]+])\\s*");
    private static final Pattern NUMBERED_RESULT_PATTERN = Pattern.compile("^\\s*\\d+[.)、]\\s*(.+)$");
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_TAG_PATTERN = Pattern.compile("(?is)<meta\\s+([^>]+)>");
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))"
    );
    private static final Pattern HTML_COMMENT_PATTERN = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern STRIP_BLOCK_PATTERN = Pattern.compile(
            "(?is)<(?:script|style|noscript|svg|iframe|nav|aside|footer|header|form)[^>]*>.*?</(?:script|style|noscript|svg|iframe|nav|aside|footer|header|form)>"
    );
    private static final Pattern BLOCK_TAG_PATTERN = Pattern.compile(
            "(?is)</?(?:p|div|section|article|main|li|ul|ol|h[1-6]|br|tr|td|blockquote)[^>]*>"
    );
    private static final Pattern ANY_TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);?");
    private static final Set<String> META_DESCRIPTION_NAMES = Set.of(
            "description",
            "og:description",
            "twitter:description"
    );
    private static final Set<String> META_TITLE_NAMES = Set.of(
            "og:title",
            "twitter:title"
    );
    private static final Set<String> NON_HTML_EXTENSIONS = Set.of(
            ".pdf", ".zip", ".rar", ".7z", ".tar", ".gz", ".tgz",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
            ".mp3", ".wav", ".mp4", ".mov", ".avi", ".mkv",
            ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx"
    );
    private static final Set<String> DOC_QUERY_CUES = Set.of(
            "doc", "docs", "documentation", "manual", "guide", "reference", "api", "sdk",
            "official", "developer", "文档", "手册", "指南", "说明", "参考", "教程", "官方", "官网"
    );
    private static final Set<String> LATEST_QUERY_CUES = Set.of(
            "latest", "today", "current", "realtime", "recent", "breaking", "news",
            "最新", "今天", "实时", "近期", "刚刚", "头条", "新闻", "快讯"
    );
    private static final Set<String> EXPLAIN_QUERY_CUES = Set.of(
            "architecture", "design", "analysis", "explain", "how", "why", "deep", "insight",
            "原理", "架构", "分析", "解读", "如何", "怎么", "原因", "设计", "方案"
    );
    private static final Set<String> COMPARE_QUERY_CUES = Set.of(
            "compare", "comparison", "vs", "versus", "difference", "对比", "区别", "比较"
    );
    private static final Set<String> OFFICIAL_RESULT_CUES = Set.of(
            "official", "developer", "docs", "documentation", "support", "reference", "api", "sdk",
            "官方", "官网", "文档", "开发者", "参考"
    );
    private static final Set<String> DOC_RESULT_CUES = Set.of(
            "docs", "documentation", "manual", "guide", "reference", "api", "sdk", "developer",
            "readme", "getting started", "quickstart", "文档", "手册", "指南", "参考", "教程", "说明"
    );
    private static final Set<String> ARTICLE_RESULT_CUES = Set.of(
            "article", "blog", "post", "insight", "analysis", "report", "news", "story",
            "文章", "博客", "解读", "观察", "分析", "报道"
    );
    private static final Set<String> LISTING_RESULT_CUES = Set.of(
            "search", "category", "categories", "tag", "tags", "topic", "topics", "archive", "all", "list", "results",
            "搜索", "分类", "标签", "专题", "归档", "列表", "结果"
    );
    private static final Set<String> GENERIC_TITLE_CUES = Set.of(
            "home", "homepage", "index", "landing", "welcome", "docs", "documentation", "search results",
            "首页", "主页", "欢迎", "搜索结果"
    );
    private static final Set<String> LATEST_RESULT_CUES = Set.of(
            "latest", "today", "update", "updated", "current", "breaking", "realtime",
            "最新", "今日", "更新", "实时", "快讯"
    );

    private final HttpClient httpClient;
    private final boolean enabled;
    private final int timeoutMs;
    private final int maxCandidates;
    private final int maxSummaryChars;
    private final int maxPageChars;

    public SearchResultDetailAugmentor(boolean enabled,
                                       int timeoutMs,
                                       int maxCandidates,
                                       int maxSummaryChars,
                                       int maxPageChars) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                enabled,
                timeoutMs,
                maxCandidates,
                maxSummaryChars,
                maxPageChars);
    }

    SearchResultDetailAugmentor(HttpClient httpClient,
                                boolean enabled,
                                int timeoutMs,
                                int maxCandidates,
                                int maxSummaryChars,
                                int maxPageChars) {
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                : httpClient;
        this.enabled = enabled;
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.maxCandidates = Math.max(1, maxCandidates);
        this.maxSummaryChars = Math.max(120, maxSummaryChars);
        this.maxPageChars = Math.max(this.maxSummaryChars, maxPageChars);
    }

    public static SearchResultDetailAugmentor disabled() {
        return new SearchResultDetailAugmentor(
                false,
                DEFAULT_TIMEOUT_MS,
                DEFAULT_MAX_CANDIDATES,
                DEFAULT_MAX_SUMMARY_CHARS,
                DEFAULT_MAX_PAGE_CHARS
        );
    }

    public String augmentRenderedSearchOutput(String query, String rawOutput) {
        if (!enabled || rawOutput == null || rawOutput.isBlank() || rawOutput.contains("最相关详情：")) {
            return rawOutput;
        }
        Optional<DetailPageBrief> detail = buildDetailBrief(query, parseRenderedItems(rawOutput));
        if (detail.isEmpty()) {
            return rawOutput;
        }
        DetailPageBrief brief = detail.get();
        return """
                我先看了和你问题最接近的一条网页（最相关详情）：
                - 标题: %s
                - 关键信息: %s
                - 详细链接: %s
                
                如果你愿意，我可以继续把这页内容展开成更完整的结论，或者再对比其他候选结果。
                """.formatted(
                safeText(brief.title()),
                safeText(brief.summary()),
                safeText(brief.link())
        ).trim();
    }

    public Optional<DetailPageBrief> buildDetailBrief(String query, List<SearchResultItem> items) {
        if (!enabled || items == null || items.isEmpty()) {
            return Optional.empty();
        }
        QueryIntentProfile profile = buildQueryProfile(query);
        List<ResultCandidate> ranked = rankCandidates(profile, items);
        List<FetchedCandidate> fetched = new ArrayList<>();
        for (int i = 0; i < ranked.size() && i < maxCandidates; i++) {
            ResultCandidate candidate = ranked.get(i);
            SearchResultItem item = candidate.item();
            String link = safeUrl(item.link());
            if (link.isBlank() || shouldSkipLink(link)) {
                continue;
            }
            PageExtract page = fetchPage(link);
            if (page == null) {
                continue;
            }
            String summary = summarizePage(query, item, page);
            if (summary.isBlank()) {
                continue;
            }
            int finalScore = candidate.score() + scoreFetchedPage(profile, item, page);
            fetched.add(new FetchedCandidate(item, page, summary, finalScore));
        }
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        fetched.sort((left, right) -> Integer.compare(right.score(), left.score()));
        FetchedCandidate best = fetched.get(0);
        return Optional.of(new DetailPageBrief(
                firstNonBlank(best.page().title(), best.item().title(), best.item().link()),
                safeUrl(best.item().link()),
                best.summary()
        ));
    }

    private List<SearchResultItem> parseRenderedItems(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, SearchResultItem> dedup = new LinkedHashMap<>();
        String pendingTitle = "";
        String pendingSummary = "";
        for (String rawLine : rawOutput.split("\\R")) {
            String line = safeText(rawLine);
            if (line.isBlank() || line.endsWith("结果：") || line.endsWith("结果:")) {
                continue;
            }
            String normalizedLine = stripLeadingMarker(line);
            if (captureMarkdownCandidates(normalizedLine, pendingTitle, pendingSummary, dedup)) {
                pendingTitle = "";
                pendingSummary = "";
                continue;
            }
            String labeledUrl = extractLabeledUrl(normalizedLine);
            if (!labeledUrl.isBlank()) {
                addCandidate(dedup, firstNonBlank(pendingTitle, guessTitle(labeledUrl)), labeledUrl, pendingSummary);
                pendingTitle = "";
                pendingSummary = "";
                continue;
            }
            Matcher numbered = NUMBERED_RESULT_PATTERN.matcher(line);
            if (numbered.matches()) {
                String payload = stripLeadingMarker(safeText(numbered.group(1)));
                if (captureMarkdownCandidates(payload, pendingTitle, pendingSummary, dedup)) {
                    pendingTitle = "";
                    pendingSummary = "";
                    continue;
                }
                String inlineUrl = firstUrl(payload);
                if (!inlineUrl.isBlank()) {
                    String beforeUrl = safeText(payload.substring(0, payload.indexOf(inlineUrl)));
                    LineParts parts = splitTitleAndSummary(beforeUrl);
                    String trailing = safeText(payload.substring(payload.indexOf(inlineUrl) + inlineUrl.length()));
                    addCandidate(
                            dedup,
                            firstNonBlank(parts.title(), inlineUrl),
                            inlineUrl,
                            firstNonBlank(parts.summary(), trailing, pendingSummary)
                    );
                    pendingTitle = "";
                    pendingSummary = "";
                    continue;
                }
                LineParts parts = splitTitleAndSummary(payload);
                pendingTitle = parts.title();
                pendingSummary = parts.summary();
                continue;
            }
            String inlineUrl = firstUrl(normalizedLine);
            if (!inlineUrl.isBlank()) {
                String beforeUrl = safeText(normalizedLine.substring(0, normalizedLine.indexOf(inlineUrl)));
                String trailing = safeText(normalizedLine.substring(normalizedLine.indexOf(inlineUrl) + inlineUrl.length()));
                LineParts parts = splitTitleAndSummary(beforeUrl);
                addCandidate(
                        dedup,
                        firstNonBlank(parts.title(), pendingTitle, guessTitle(inlineUrl)),
                        inlineUrl,
                        firstNonBlank(parts.summary(), trailing, pendingSummary)
                );
                pendingTitle = "";
                pendingSummary = "";
                continue;
            }
            if (pendingTitle.isBlank()) {
                pendingTitle = normalizedLine;
            } else if (pendingSummary.isBlank()) {
                pendingSummary = normalizedLine;
            }
        }
        return List.copyOf(dedup.values());
    }

    private List<ResultCandidate> rankCandidates(QueryIntentProfile profile, List<SearchResultItem> items) {
        List<ResultCandidate> ranked = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SearchResultItem item = items.get(i);
            if (item == null || safeUrl(item.link()).isBlank()) {
                continue;
            }
            int score = Math.max(0, 100 - (i * 8));
            score += scoreSemanticMatch(profile, item);
            score += scoreIntentAlignment(profile, item);
            ranked.add(new ResultCandidate(item, score));
        }
        ranked.sort((left, right) -> Integer.compare(right.score(), left.score()));
        return List.copyOf(ranked);
    }

    private boolean captureMarkdownCandidates(String line,
                                              String fallbackTitle,
                                              String fallbackSummary,
                                              LinkedHashMap<String, SearchResultItem> dedup) {
        if (line == null || line.isBlank()) {
            return false;
        }
        boolean captured = false;
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(line);
        while (matcher.find()) {
            String title = safeText(matcher.group(1));
            String url = safeText(matcher.group(2));
            String remainder = safeText((line.substring(0, matcher.start()) + " " + line.substring(matcher.end()))
                    .replace("()", " ")
                    .replaceAll("\\s+", " "));
            LineParts parts = splitTitleAndSummary(remainder);
            addCandidate(
                    dedup,
                    firstNonBlank(title, parts.title(), fallbackTitle, guessTitle(url)),
                    url,
                    firstNonBlank(parts.summary(), remainder, fallbackSummary)
            );
            captured = true;
        }
        return captured;
    }

    private void addCandidate(LinkedHashMap<String, SearchResultItem> dedup,
                              String title,
                              String url,
                              String summary) {
        String safeLink = safeUrl(url);
        if (dedup == null || safeLink.isBlank()) {
            return;
        }
        dedup.putIfAbsent(normalizeKey(safeLink), new SearchResultItem(
                firstNonBlank(title, safeLink),
                safeLink,
                safeText(summary),
                Instant.EPOCH,
                ""
        ));
    }

    private String extractLabeledUrl(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        Matcher matcher = LABELED_URL_PATTERN.matcher(line);
        return matcher.matches() ? trimTrailingPunctuation(matcher.group(1)) : "";
    }

    private String stripLeadingMarker(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        return safeText(LEADING_MARKER_PATTERN.matcher(line).replaceFirst(""));
    }

    private PageExtract fetchPage(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.2")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            byte[] bytes;
            try (InputStream stream = response.body()) {
                if (!looksReadableContentType(contentType)) {
                    return null;
                }
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length == 0) {
                return null;
            }
            String body = new String(
                    bytes,
                    0,
                    Math.min(bytes.length, MAX_RESPONSE_BYTES),
                    resolveCharset(contentType)
            );
            if (contentType.toLowerCase(Locale.ROOT).contains("text/plain")) {
                return new PageExtract("", "", capText(normalizeWhitespace(body), maxPageChars));
            }
            return extractHtmlPage(body);
        } catch (IllegalArgumentException | IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, "search detail fetch failed for " + url, ex);
            return null;
        }
    }

    private boolean looksReadableContentType(String contentType) {
        String normalized = safeText(contentType).toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.contains("text/html")
                || normalized.contains("application/xhtml+xml")
                || normalized.contains("text/plain");
    }

    private Charset resolveCharset(String contentType) {
        String normalized = safeText(contentType);
        int charsetIndex = normalized.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (charsetIndex < 0) {
            return StandardCharsets.UTF_8;
        }
        String charsetName = normalized.substring(charsetIndex + "charset=".length()).trim();
        int separator = charsetName.indexOf(';');
        if (separator >= 0) {
            charsetName = charsetName.substring(0, separator).trim();
        }
        if (charsetName.startsWith("\"") && charsetName.endsWith("\"") && charsetName.length() > 1) {
            charsetName = charsetName.substring(1, charsetName.length() - 1);
        }
        try {
            return Charset.forName(charsetName);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private PageExtract extractHtmlPage(String html) {
        String title = firstNonBlank(
                extractMetaContent(html, META_TITLE_NAMES),
                cleanExtractedText(extractFirst(TITLE_TAG_PATTERN, html))
        );
        String description = extractMetaContent(html, META_DESCRIPTION_NAMES);
        String mainHtml = firstNonBlank(
                extractLongestSection(html, "article"),
                extractLongestSection(html, "main"),
                extractLongestSection(html, "body"),
                html
        );
        String text = cleanExtractedText(mainHtml);
        return new PageExtract(title, description, capText(text, maxPageChars));
    }

    private String extractMetaContent(String html, Set<String> acceptedNames) {
        if (html == null || html.isBlank() || acceptedNames == null || acceptedNames.isEmpty()) {
            return "";
        }
        Matcher matcher = META_TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            Map<String, String> attributes = parseAttributes(matcher.group(1));
            String name = normalizeKey(firstNonBlank(
                    attributes.get("name"),
                    attributes.get("property"),
                    attributes.get("itemprop")
            ));
            if (!acceptedNames.contains(name)) {
                continue;
            }
            return cleanExtractedText(attributes.get("content"));
        }
        return "";
    }

    private Map<String, String> parseAttributes(String rawAttributes) {
        if (rawAttributes == null || rawAttributes.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(rawAttributes);
        while (matcher.find()) {
            String key = normalizeKey(matcher.group(1));
            String value = firstNonBlank(matcher.group(3), matcher.group(4), matcher.group(5));
            if (!key.isBlank() && !value.isBlank()) {
                attributes.putIfAbsent(key, value);
            }
        }
        return attributes;
    }

    private String extractLongestSection(String html, String tagName) {
        if (html == null || html.isBlank() || tagName == null || tagName.isBlank()) {
            return "";
        }
        Pattern pattern = Pattern.compile("(?is)<" + Pattern.quote(tagName) + "\\b[^>]*>(.*?)</" + Pattern.quote(tagName) + ">");
        Matcher matcher = pattern.matcher(html);
        String longest = "";
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate != null && candidate.length() > longest.length()) {
                longest = candidate;
            }
        }
        return longest;
    }

    private String summarizePage(String query, SearchResultItem item, PageExtract page) {
        String content = firstNonBlank(
                joinDistinct(page.description(), page.text()),
                joinDistinct(item.summary(), page.text()),
                joinDistinct(page.description(), item.summary())
        );
        if (content.isBlank()) {
            return "";
        }
        List<String> fragments = splitFragments(content);
        if (fragments.isEmpty()) {
            return capText(content, maxSummaryChars);
        }
        List<String> queryTerms = splitTerms(query);
        List<ScoredFragment> scored = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            String fragment = fragments.get(i);
            int score = Math.max(0, 100 - (i * 6));
            String normalized = normalizeText(fragment);
            for (String term : queryTerms) {
                if (normalized.contains(term)) {
                    score += 8;
                }
            }
            if (!normalizeText(item.title()).isBlank() && normalized.contains(normalizeText(item.title()))) {
                score += 6;
            }
            scored.add(new ScoredFragment(fragment, score));
        }
        scored.sort((left, right) -> Integer.compare(right.score(), left.score()));
        List<String> selected = new ArrayList<>();
        for (ScoredFragment fragment : scored) {
            if (selected.size() >= 2) {
                break;
            }
            String value = safeText(fragment.value());
            if (value.isBlank() || selected.stream().anyMatch(existing -> existing.contains(value) || value.contains(existing))) {
                continue;
            }
            selected.add(value);
        }
        if (selected.isEmpty()) {
            selected.add(fragments.get(0));
        }
        return capText(String.join(" ", selected), maxSummaryChars);
    }

    private QueryIntentProfile buildQueryProfile(String query) {
        String normalizedQuery = normalizeText(query);
        List<String> terms = splitTerms(query);
        return new QueryIntentProfile(
                normalizedQuery,
                terms,
                containsAny(normalizedQuery, DOC_QUERY_CUES),
                containsAny(normalizedQuery, LATEST_QUERY_CUES),
                normalizedQuery.contains("official") || normalizedQuery.contains("官方") || normalizedQuery.contains("官网"),
                containsAny(normalizedQuery, EXPLAIN_QUERY_CUES),
                containsAny(normalizedQuery, COMPARE_QUERY_CUES)
        );
    }

    private int scoreSemanticMatch(QueryIntentProfile profile, SearchResultItem item) {
        String title = normalizeText(item.title());
        String summary = normalizeText(item.summary());
        ParsedLink parsedLink = parseLink(item.link());
        String urlText = normalizeText(parsedLink.host() + " " + parsedLink.path());
        int score = 0;
        if (!profile.normalizedQuery().isBlank()) {
            if (title.contains(profile.normalizedQuery())) {
                score += 30;
            } else if (summary.contains(profile.normalizedQuery())) {
                score += 18;
            } else if (urlText.contains(profile.normalizedQuery())) {
                score += 8;
            }
        }
        int titleCoverage = coverageCount(profile.terms(), title);
        int summaryCoverage = coverageCount(profile.terms(), summary);
        int urlCoverage = coverageCount(profile.terms(), urlText);
        score += titleCoverage * 9;
        score += summaryCoverage * 5;
        score += urlCoverage * 2;
        int distinctCoverage = coverageCount(profile.terms(), title + " " + summary + " " + urlText);
        if (!profile.terms().isEmpty() && distinctCoverage == profile.terms().size()) {
            score += 12;
        } else if (distinctCoverage >= Math.max(2, profile.terms().size() - 1)) {
            score += 6;
        }
        if (safeText(item.summary()).length() >= 24) {
            score += 3;
        }
        return score;
    }

    private int scoreIntentAlignment(QueryIntentProfile profile, SearchResultItem item) {
        ParsedLink parsedLink = parseLink(item.link());
        String title = normalizeText(item.title());
        String summary = normalizeText(item.summary());
        String urlText = normalizeText(parsedLink.host() + " " + parsedLink.path());
        String haystack = title + " " + summary + " " + urlText;
        boolean docsLike = looksDocsLike(haystack, parsedLink);
        boolean officialLike = looksOfficialLike(haystack, parsedLink);
        boolean articleLike = looksArticleLike(haystack, parsedLink);
        boolean listingLike = looksListingLike(haystack, parsedLink);
        boolean homepage = parsedLink.homepage();
        int score = 0;

        if (profile.docsIntent()) {
            if (docsLike) {
                score += 26;
            }
            if (officialLike) {
                score += 12;
            }
            if (homepage) {
                score -= 16;
            }
            if (listingLike) {
                score -= 10;
            }
            if (!docsLike && articleLike) {
                score -= 6;
            }
        }
        if (profile.officialIntent()) {
            score += officialLike ? 10 : -4;
        }
        if (profile.latestIntent()) {
            score += recencyScore(item.publishedAt());
            if (containsAny(haystack, LATEST_RESULT_CUES)) {
                score += 6;
            }
        }
        if (profile.explainerIntent()) {
            if (articleLike) {
                score += 12;
            }
            if (docsLike) {
                score += 4;
            }
            if (homepage) {
                score -= 8;
            }
        }
        if (profile.compareIntent() && (haystack.contains(" vs ") || haystack.contains(" 对比 ") || haystack.contains(" 比较 "))) {
            score += 10;
        }
        if (!profile.docsIntent() && articleLike) {
            score += 6;
        }
        if (homepage) {
            score -= 10;
        }
        if (listingLike) {
            score -= 8;
        }
        if (containsAny(title, GENERIC_TITLE_CUES) && !profile.docsIntent()) {
            score -= 6;
        }
        return score;
    }

    private int scoreFetchedPage(QueryIntentProfile profile, SearchResultItem item, PageExtract page) {
        String text = normalizeText(
                firstNonBlank(page.title(), "")
                        + " "
                        + firstNonBlank(page.description(), "")
                        + " "
                        + capText(firstNonBlank(page.text(), ""), 1200)
        );
        ParsedLink parsedLink = parseLink(item.link());
        int score = 0;
        if (!profile.normalizedQuery().isBlank()) {
            if (normalizeText(page.title()).contains(profile.normalizedQuery())) {
                score += 22;
            } else if (normalizeText(page.description()).contains(profile.normalizedQuery())) {
                score += 12;
            }
        }
        score += coverageCount(profile.terms(), normalizeText(page.title())) * 7;
        score += coverageCount(profile.terms(), normalizeText(page.description())) * 5;
        score += coverageCount(profile.terms(), text) * 3;
        if (profile.docsIntent() && looksDocsLike(text, parsedLink)) {
            score += 12;
        }
        if (profile.officialIntent() && looksOfficialLike(text, parsedLink)) {
            score += 8;
        }
        if (profile.explainerIntent() && looksArticleLike(text, parsedLink)) {
            score += 8;
        }
        if (!page.text().isBlank() && page.text().length() >= 180) {
            score += 4;
        }
        return score;
    }

    private int recencyScore(Instant publishedAt) {
        if (publishedAt == null || Instant.EPOCH.equals(publishedAt)) {
            return 0;
        }
        long hours = Duration.between(publishedAt, Instant.now()).toHours();
        if (hours <= 24) {
            return 18;
        }
        if (hours <= 72) {
            return 12;
        }
        if (hours <= 24L * 7) {
            return 8;
        }
        if (hours <= 24L * 30) {
            return 4;
        }
        return 0;
    }

    private int coverageCount(List<String> terms, String haystack) {
        if (terms == null || terms.isEmpty() || haystack == null || haystack.isBlank()) {
            return 0;
        }
        int covered = 0;
        for (String term : terms) {
            if (!term.isBlank() && haystack.contains(term)) {
                covered++;
            }
        }
        return covered;
    }

    private boolean looksDocsLike(String haystack, ParsedLink parsedLink) {
        String path = parsedLink == null ? "" : parsedLink.path();
        return containsAny(haystack, DOC_RESULT_CUES)
                || path.contains("/docs")
                || path.contains("/reference")
                || path.contains("/api")
                || path.contains("/guide")
                || path.contains("/manual");
    }

    private boolean looksOfficialLike(String haystack, ParsedLink parsedLink) {
        String host = parsedLink == null ? "" : parsedLink.host();
        return containsAny(haystack, OFFICIAL_RESULT_CUES)
                || host.startsWith("docs.")
                || host.startsWith("developer.")
                || host.contains(".gov")
                || host.contains(".edu");
    }

    private boolean looksArticleLike(String haystack, ParsedLink parsedLink) {
        String path = parsedLink == null ? "" : parsedLink.path();
        return containsAny(haystack, ARTICLE_RESULT_CUES)
                || path.contains("/blog/")
                || path.contains("/news/")
                || path.contains("/article/")
                || path.contains("/post/")
                || path.matches(".*/20\\d{2}/.*");
    }

    private boolean looksListingLike(String haystack, ParsedLink parsedLink) {
        String path = parsedLink == null ? "" : parsedLink.path();
        return containsAny(haystack, LISTING_RESULT_CUES)
                || path.contains("/search")
                || path.contains("/tag/")
                || path.contains("/category/")
                || path.contains("/topics/")
                || path.contains("/archive");
    }

    private ParsedLink parseLink(String rawUrl) {
        String safe = safeUrl(rawUrl);
        if (safe.isBlank()) {
            return new ParsedLink("", "", true);
        }
        try {
            URI uri = URI.create(safe);
            String rawPath = safeText(firstNonBlank(uri.getPath(), ""));
            String host = safeText(firstNonBlank(uri.getHost(), "")).toLowerCase(Locale.ROOT);
            String path = rawPath.toLowerCase(Locale.ROOT);
            boolean homepage = path.isBlank()
                    || "/".equals(rawPath)
                    || "/index".equalsIgnoreCase(rawPath)
                    || "/home".equalsIgnoreCase(rawPath);
            return new ParsedLink(host, path, homepage);
        } catch (IllegalArgumentException ex) {
            return new ParsedLink("", "", true);
        }
    }

    private boolean containsAny(String haystack, Set<String> cues) {
        if (haystack == null || haystack.isBlank() || cues == null || cues.isEmpty()) {
            return false;
        }
        String normalizedHaystack = haystack.toLowerCase(Locale.ROOT);
        for (String cue : cues) {
            if (cue != null && !cue.isBlank() && normalizedHaystack.contains(cue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitFragments(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> fragments = new ArrayList<>();
        for (String paragraph : content.split("\\n+")) {
            String normalizedParagraph = safeText(paragraph);
            if (normalizedParagraph.isBlank()) {
                continue;
            }
            String[] pieces = normalizedParagraph.split("(?<=[。！？!?；;])|(?<=\\.)\\s+");
            if (pieces.length == 1) {
                addFragment(fragments, normalizedParagraph);
                continue;
            }
            for (String piece : pieces) {
                addFragment(fragments, piece);
            }
        }
        return List.copyOf(fragments);
    }

    private void addFragment(List<String> fragments, String raw) {
        String fragment = safeText(raw);
        if (fragment.length() < 16) {
            return;
        }
        if (fragment.length() > 260) {
            fragment = fragment.substring(0, 260).trim();
        }
        fragments.add(fragment);
    }

    private String cleanExtractedText(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "";
        }
        String cleaned = HTML_COMMENT_PATTERN.matcher(rawHtml).replaceAll(" ");
        cleaned = STRIP_BLOCK_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = BLOCK_TAG_PATTERN.matcher(cleaned).replaceAll("\n");
        cleaned = ANY_TAG_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = decodeHtmlEntities(cleaned);
        return normalizeWhitespace(cleaned);
    }

    private String decodeHtmlEntities(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String decoded = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(decoded);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            String replacement;
            try {
                int codePoint = raw.startsWith("x") || raw.startsWith("X")
                        ? Integer.parseInt(raw.substring(1), 16)
                        : Integer.parseInt(raw, 10);
                replacement = new String(Character.toChars(codePoint));
            } catch (RuntimeException ex) {
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String normalizeWhitespace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace('\r', '\n');
        normalized = normalized.replaceAll("[\\t\\x0B\\f ]+", " ");
        normalized = normalized.replaceAll("\\s*\\n\\s*", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private String joinDistinct(String left, String right) {
        String first = safeText(left);
        String second = safeText(right);
        if (first.isBlank()) {
            return second;
        }
        if (second.isBlank() || normalizeText(second).contains(normalizeText(first))) {
            return first;
        }
        if (normalizeText(first).contains(normalizeText(second))) {
            return first;
        }
        return first + "\n" + second;
    }

    private List<String> splitTerms(String query) {
        String normalized = normalizeText(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        String segmented = normalized.replaceAll("(?<=[\\p{IsHan}])(?=[\\p{L}\\p{N}])|(?<=[\\p{L}\\p{N}])(?=[\\p{IsHan}])", " ");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : segmented.split("\\s+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        return List.copyOf(terms);
    }

    private boolean shouldSkipLink(String link) {
        String normalized = safeUrl(link).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(normalized);
            String path = safeText(uri.getPath()).toLowerCase(Locale.ROOT);
            for (String suffix : NON_HTML_EXTENSIONS) {
                if (path.endsWith(suffix)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        return false;
    }

    private String safeUrl(String rawUrl) {
        String normalized = safeText(rawUrl);
        if (normalized.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(trimTrailingPunctuation(normalized));
            if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
                return "";
            }
            return uri.toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String trimTrailingPunctuation(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last == ')' || last == ']' || last == '}' || last == ',' || last == '，' || last == '。') {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
                continue;
            }
            break;
        }
        return trimmed;
    }

    private String firstUrl(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        return matcher.find() ? trimTrailingPunctuation(matcher.group(1)) : "";
    }

    private String guessTitle(String url) {
        try {
            URI uri = URI.create(url);
            String host = safeText(uri.getHost());
            if (!host.isBlank()) {
                return host;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return url;
    }

    private LineParts splitTitleAndSummary(String payload) {
        String normalized = safeText(payload);
        int separator = normalized.indexOf(" - ");
        if (separator < 0) {
            return new LineParts(normalized, "");
        }
        return new LineParts(
                safeText(normalized.substring(0, separator)),
                safeText(normalized.substring(separator + 3))
        );
    }

    private String capText(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 1)).trim() + "…";
    }

    private String normalizeText(String value) {
        return safeText(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String normalizeKey(String value) {
        return safeText(value).toLowerCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String extractFirst(Pattern pattern, String source) {
        if (pattern == null || source == null || source.isBlank()) {
            return "";
        }
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? safeText(matcher.group(1)) : "";
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

    public record DetailPageBrief(String title, String link, String summary) {
    }

    private record ResultCandidate(SearchResultItem item, int score) {
    }

    private record FetchedCandidate(SearchResultItem item, PageExtract page, String summary, int score) {
    }

    private record LineParts(String title, String summary) {
    }

    private record PageExtract(String title, String description, String text) {
    }

    private record ScoredFragment(String value, int score) {
    }

    private record ParsedLink(String host, String path, boolean homepage) {
    }

    private record QueryIntentProfile(String normalizedQuery,
                                      List<String> terms,
                                      boolean docsIntent,
                                      boolean latestIntent,
                                      boolean officialIntent,
                                      boolean explainerIntent,
                                      boolean compareIntent) {
    }
}

package com.zhongbo.mindos.assistant.skill.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import com.zhongbo.mindos.assistant.skill.search.SearchProviderChain;
import com.zhongbo.mindos.assistant.skill.search.SearchProviderRegistry;
import com.zhongbo.mindos.assistant.skill.search.SearchRequest;
import com.zhongbo.mindos.assistant.skill.search.SearchResultItem;
import com.zhongbo.mindos.assistant.skill.search.SearchSourceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import com.zhongbo.mindos.assistant.skill.examples.util.TitleCleaner;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NewsSearchSkill implements Skill, SkillDescriptorProvider {

    private static final Logger LOGGER = Logger.getLogger(NewsSearchSkill.class.getName());
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern SOURCE_PATTERN = Pattern.compile("(?i)(?:^|\\s)source\\s*=\\s*([\\p{L}\\p{N}._-]+)(?:\\s|$)");
    private static final Pattern SORT_PATTERN = Pattern.compile("(?i)(?:^|\\s)sort\\s*=\\s*(latest|relevance)(?:\\s|$)");
    private static final Pattern LIMIT_PARAM_PATTERN = Pattern.compile("(?i)(?:^|\\s)limit\\s*=\\s*([0-9一二两三四五六七八九十百千万]+)(?:\\s|$)");
    private static final Pattern COUNT_LIMIT_PATTERN = Pattern.compile("(?:前|看|来|给我看|给我|帮我看)?\\s*([0-9一二两三四五六七八九十百千万]+)\\s*(?:条|篇|个)");
    private static final Pattern PARAMETER_TOKEN_PATTERN = Pattern.compile("(?i)\\b(source|sort|limit)\\s*=\\s*[^\\s]+");
    private static final Pattern SOURCE_TOKEN_PATTERN = Pattern.compile("(?i)(?:36kr|36氪|serper|serpapi)");
    private static final Pattern LEADING_QUERY_NOISE_PATTERN = Pattern.compile("^(?:只看|仅看|只要|仅限|帮我看|给我看|看|查|搜|搜索|查询|请|请帮我)?\\s*");
    private static final Pattern LEADING_TOPIC_CONNECTOR_PATTERN = Pattern.compile("^(?:的|关于|有关|针对|围绕)\\s*");
    private static final Pattern RECENCY_NOISE_PATTERN = Pattern.compile("(?:今天的|今日的|今天|今日|最新的|最新|最近的|最近|实时的|实时)");
    private static final Pattern TRAILING_NEWS_NOISE_PATTERN = Pattern.compile("(?:新闻|资讯|消息|头条|热点|热搜)+$");
    private static final Pattern TRAILING_ACTION_NOISE_PATTERN = Pattern.compile(
            "(?:并|顺便)?\\s*(?:总结(?:一下)?|汇总(?:一下)?|梳理(?:一下|下)?|解读(?:一下)?|分析(?:一下)?|概括(?:一下)?|整理(?:一下)?)(?:吧|呀|一下)?$"
    );
    private static final Pattern NATURAL_NEWS_TRIGGER_PATTERN = Pattern.compile("(?:查看|看|查询|查|搜索|搜).{0,12}(?:新闻|资讯|头条)|(?:新闻|资讯|头条).{0,12}(?:查看|看|查询|查|搜索|搜)");
    private static final Pattern PLAIN_NEWS_REQUEST_PATTERN = Pattern.compile(
            "(?:今天|今日|最新|最近|实时|国际|国内|财经|科技|ai|人工智能|股市|市场|融资|头条)?.{0,18}(?:新闻|资讯|消息|头条|热点|热搜)"
    );
    private static final Set<String> HOT_KEYWORD_STOP_WORDS = Set.of(
            "google", "news", "rss", "36kr", "today", "with", "from", "that", "this", "以及", "相关", "新闻", "今日", "最新", "报道"
    );

    private final LlmClient llmClient;
    private final NewsFeedFetcher newsFeedFetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String krFeedUrl;
    private final int httpTimeoutMs;
    private final int cacheTtlSeconds;
    private final int cacheMaxEntries;
    private final int maxItems;
    private final boolean summaryEnabled;
    private final String summaryProvider;
    private final String summaryPreset;
    private final String summaryModel;
    private final int summaryMaxTokens;
    private final List<SearchSourceConfig> searchSources;
    private final SearchProviderChain searchProviderChain;
    private final boolean serperEnabled;
    private final String serperNewsUrl;
    private final String serperSearchUrl;
    private final String serperApiKey;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public NewsSearchSkill(LlmClient llmClient,
                           @Value("${mindos.skill.news-search.enabled:true}") boolean enabled,
                           @Value("${mindos.skill.news-search.kr-feed-url:https://36kr.com/feed}") String krFeedUrl,
                           @Value("${mindos.skill.news-search.http-timeout-ms:5000}") int httpTimeoutMs,
                           @Value("${mindos.skill.news-search.cache-ttl-seconds:300}") int cacheTtlSeconds,
                           @Value("${mindos.skill.news-search.cache-max-entries:128}") int cacheMaxEntries,
                           @Value("${mindos.skill.news-search.max-items:8}") int maxItems,
                            @Value("${mindos.skill.news-search.summary-enabled:true}") boolean summaryEnabled,
                            @Value("${mindos.skill.news-search.summary-provider:}") String summaryProvider,
                           @Value("${mindos.skill.news-search.summary-preset:cost}") String summaryPreset,
                           @Value("${mindos.skill.news-search.summary-model:gemma3:1b-it-q4_K_M}") String summaryModel,
                            @Value("${mindos.skill.news-search.summary-max-tokens:220}") int summaryMaxTokens,
                             @Value("${mindos.skills.search-sources:}") String searchSources,
                             @Value("${mindos.skill.news-search.serper.enabled:false}") boolean serperEnabled,
                            @Value("${mindos.skill.news-search.serper.news-url:https://google.serper.dev/news}") String serperNewsUrl,
                            @Value("${mindos.skill.news-search.serper.search-url:https://google.serper.dev/search}") String serperSearchUrl,
                            @Value("${mindos.skill.news-search.serper.api-key:}") String serperApiKey) {
        this(llmClient,
                new DefaultNewsFeedFetcher(),
                enabled,
                krFeedUrl,
                httpTimeoutMs,
                cacheTtlSeconds,
                cacheMaxEntries,
                maxItems,
                summaryEnabled,
                summaryProvider,
                summaryPreset,
                summaryModel,
                summaryMaxTokens,
                searchSources,
                serperEnabled,
                serperNewsUrl,
                serperSearchUrl,
                serperApiKey);
    }

    NewsSearchSkill(LlmClient llmClient,
                    NewsFeedFetcher newsFeedFetcher,
                    boolean enabled,
                    String krFeedUrl,
                    int httpTimeoutMs,
                    int cacheTtlSeconds,
                    int cacheMaxEntries,
                    int maxItems,
                    boolean summaryEnabled,
                    String summaryProvider,
                    String summaryPreset,
                    String summaryModel,
                    int summaryMaxTokens) {
        this(llmClient,
                newsFeedFetcher,
                enabled,
                krFeedUrl,
                httpTimeoutMs,
                cacheTtlSeconds,
                cacheMaxEntries,
                maxItems,
                summaryEnabled,
                summaryProvider,
                summaryPreset,
                summaryModel,
                summaryMaxTokens,
                "",
                false,
                "",
                "",
                "");
    }

    NewsSearchSkill(LlmClient llmClient,
                    NewsFeedFetcher newsFeedFetcher,
                    boolean enabled,
                    String krFeedUrl,
                    int httpTimeoutMs,
                    int cacheTtlSeconds,
                    int cacheMaxEntries,
                    int maxItems,
                    boolean summaryEnabled,
                    String summaryProvider,
                    String summaryPreset,
                    String summaryModel,
                    int summaryMaxTokens,
                    boolean serperEnabled,
                    String serperNewsUrl,
                    String serperSearchUrl,
                    String serperApiKey) {
        this(llmClient,
                newsFeedFetcher,
                enabled,
                krFeedUrl,
                httpTimeoutMs,
                cacheTtlSeconds,
                cacheMaxEntries,
                maxItems,
                summaryEnabled,
                summaryProvider,
                summaryPreset,
                summaryModel,
                summaryMaxTokens,
                "",
                serperEnabled,
                serperNewsUrl,
                serperSearchUrl,
                serperApiKey);
    }

    NewsSearchSkill(LlmClient llmClient,
                    NewsFeedFetcher newsFeedFetcher,
                    boolean enabled,
                    String krFeedUrl,
                    int httpTimeoutMs,
                    int cacheTtlSeconds,
                    int cacheMaxEntries,
                    int maxItems,
                    boolean summaryEnabled,
                    String summaryProvider,
                    String summaryPreset,
                    String summaryModel,
                    int summaryMaxTokens,
                    String searchSources,
                    boolean serperEnabled,
                    String serperNewsUrl,
                    String serperSearchUrl,
                    String serperApiKey) {
        this.llmClient = llmClient;
        this.newsFeedFetcher = newsFeedFetcher;
        this.enabled = enabled;
        this.krFeedUrl = krFeedUrl == null || krFeedUrl.isBlank() ? "https://36kr.com/feed" : krFeedUrl.trim();
        this.httpTimeoutMs = Math.max(1000, httpTimeoutMs);
        this.cacheTtlSeconds = Math.max(10, cacheTtlSeconds);
        this.cacheMaxEntries = Math.max(16, cacheMaxEntries);
        this.maxItems = Math.max(3, maxItems);
        this.summaryEnabled = summaryEnabled;
        // Default to empty so the global LLM provider or explicit env/config can control cloud routing.
        this.summaryProvider = summaryProvider == null ? "" : summaryProvider.trim();
        this.summaryPreset = summaryPreset == null ? "" : summaryPreset.trim();
        this.summaryModel = summaryModel == null || summaryModel.isBlank() ? "gemma3:1b-it-q4_K_M" : summaryModel.trim();
        this.summaryMaxTokens = Math.max(80, summaryMaxTokens);
        this.searchSources = SearchSourceConfig.parseList(searchSources);
        this.searchProviderChain = SearchProviderRegistry.standard();
        this.serperEnabled = serperEnabled;
        this.serperNewsUrl = serperNewsUrl == null ? "" : serperNewsUrl.trim();
        this.serperSearchUrl = serperSearchUrl == null ? "" : serperSearchUrl.trim();
        this.serperApiKey = serperApiKey == null ? "" : serperApiKey.trim();
    }

    @Override
    public String name() {
        return "news_search";
    }

    @Override
    public String description() {
        return "聚合 36kr 新闻，并在失败时回退到 Serper / SerpApi 搜索后生成简要摘要。";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(
                name(),
                description(),
                List.of("news_search", "news search", "36kr", "serper", "serpapi", "新闻检索", "新闻搜索", "查看新闻", "看新闻", "新闻", "资讯", "头条", "热点", "热搜")
        );
    }

    @Override
    public SkillResult run(SkillContext context) {
        if (!enabled) {
            return SkillResult.failure(name(), "news_search 已禁用，请联系管理员开启。");
        }
        String query = resolveQuery(context);
        if (query.isBlank()) {
            return SkillResult.failure(name(), "请提供新闻关键词，例如：news_search AI 芯片");
        }
        SourceSelection sourceSelection = resolveSource(context);
        SortMode sortMode = resolveSort(context);
        int requestedLimit = resolveLimit(context);
        int effectiveLimit = Math.min(requestedLimit, maxItems);
        String cacheKey = normalize(query) + "|" + sourceSelection.cacheKey() + "|" + sortMode.cacheKey() + "|" + effectiveLimit;
        CacheEntry cacheEntry = loadWithCache(cacheKey, query, effectiveLimit, sourceSelection, sortMode);
        List<NewsItem> items = cacheEntry.items();
        if (items.isEmpty()) {
            return SkillResult.success(name(), "[news_search]\n未获取到相关新闻，请稍后重试。关键词：" + query);
        }

        SummaryBundle summaryBundle = cacheEntry.summaryBundle();
        if (summaryBundle == null) {
            summaryBundle = summarize(query, items, context);
            cache.put(cacheKey, new CacheEntry(items, summaryBundle, cacheEntry.expiresAtMs()));
        }
        return SkillResult.success(name(), renderOutput(query, sourceSelection, sortMode, items, summaryBundle));
    }

    private CacheEntry loadWithCache(String cacheKey, String query, int limit, SourceSelection sourceSelection, SortMode sortMode) {
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached;
        }
        List<NewsItem> fetched = fetchNews(query, limit, sourceSelection, sortMode);
        CacheEntry entry = new CacheEntry(fetched, null, now + cacheTtlSeconds * 1000L);
        cache.put(cacheKey, entry);
        cleanupExpiredCache(now);
        return entry;
    }

    private List<NewsItem> fetchNews(String query, int limit, SourceSelection sourceSelection, SortMode sortMode) {
        List<NewsItem> merged = new ArrayList<>();
        FetchPlan fetchPlan = buildFetchPlan(sourceSelection);
        logFetchPlan(query, fetchPlan);

        if (fetchPlan.preferConfiguredSources()) {
            merged.addAll(fetchConfiguredSourceItems(query, fetchPlan.selectedAlias(), "configured-primary", "36kr"));
        }

        KrFetchResult krFetch = fetch36KrIfNeeded(query, sourceSelection, fetchPlan, merged.isEmpty());
        merged.addAll(krFetch.items());

        if (merged.isEmpty() && shouldTryConfiguredFallback(sourceSelection, fetchPlan)) {
            if (krFetch.attempted() && krFetch.failed()) {
                LOGGER.log(Level.INFO, "36kr failed, switching to Serper fallback for query: " + query);
            }
            merged.addAll(fetchConfiguredSourceItems(query, fetchPlan.selectedAlias(), "configured-fallback", null));
        }

        return finalizeFetchedItems(query, limit, sortMode, merged);
    }

    private String renderOutput(String query,
                                SourceSelection sourceSelection,
                                SortMode sortMode,
                                List<NewsItem> items,
                                SummaryBundle summaryBundle) {
        StringBuilder output = new StringBuilder();
        output.append("[news_search]\n关键词: ").append(query).append("\n");
        appendOutputLine(output, "主题", summaryBundle.theme());
        if (!summaryBundle.hotKeywords().isEmpty()) {
            output.append("热点关键词: ").append(String.join("、", summaryBundle.hotKeywords())).append("\n");
        }
        appendOutputLine(output, "摘要", summaryBundle.summary());
        appendOutputLine(output, "上下文总结", summaryBundle.contextBrief());
        output.append("来源: ").append(describeSource(sourceSelection, items)).append("\n");
        output.append("排序: ").append(sortMode.displayName()).append("\n\n");
        appendOutputItems(output, items);
        return output.toString().trim();
    }

    private void appendOutputLine(StringBuilder output, String label, String value) {
        if (value != null && !value.isBlank()) {
            output.append(label).append(": ").append(value.trim()).append("\n");
        }
    }

    private void appendOutputItems(StringBuilder output, List<NewsItem> items) {
        for (int i = 0; i < items.size(); i++) {
            NewsItem item = items.get(i);
            output.append(i + 1)
                    .append(". ")
                    .append(item.title())
                    .append(" [")
                    .append(item.source())
                    .append("]\n")
                    .append("   ")
                    .append(item.link())
                    .append("\n");
        }
    }

    private FetchPlan buildFetchPlan(SourceSelection sourceSelection) {
        SourceMode mode = sourceSelection == null || sourceSelection.mode() == null ? SourceMode.ALL : sourceSelection.mode();
        String selectedAlias = sourceSelection == null ? "" : sourceSelection.alias();
        boolean explicitlySpecified = sourceSelection != null && sourceSelection.explicitlySpecified();
        boolean customSelected = selectedAlias != null && !selectedAlias.isBlank();
        boolean preferConfiguredSources = !customSelected
                && sourceSelection != null
                && !explicitlySpecified
                && !searchSources.isEmpty();
        return new FetchPlan(mode, selectedAlias, explicitlySpecified, customSelected, preferConfiguredSources);
    }

    private void logFetchPlan(String query, FetchPlan fetchPlan) {
        logSourceDecision("select",
                "query=" + clipForLog(query)
                        + ", mode=" + (fetchPlan == null || fetchPlan.mode() == null ? "all" : fetchPlan.mode().cacheKey())
                        + ", alias=" + (fetchPlan == null || fetchPlan.selectedAlias() == null || fetchPlan.selectedAlias().isBlank() ? "-" : fetchPlan.selectedAlias())
                        + ", explicit=" + (fetchPlan != null && fetchPlan.explicitlySpecified())
                        + ", preferConfigured=" + (fetchPlan != null && fetchPlan.preferConfiguredSources())
                        + ", configuredSources=" + searchSources.size());
    }

    private List<NewsItem> fetchConfiguredSourceItems(String query,
                                                      String selectedAlias,
                                                      String stagePrefix,
                                                      String fallbackLabel) {
        try {
            for (SearchSourceConfig source : configuredSearchSources(selectedAlias)) {
                List<NewsItem> sourceItems = fetchSearchProvider(source, query);
                if (!sourceItems.isEmpty()) {
                    logSourceDecision(stagePrefix + "-hit",
                            "query=" + clipForLog(query)
                                    + ", source=" + source.alias()
                                    + ", items=" + sourceItems.size());
                    return sourceItems;
                }
                logSourceDecision(stagePrefix + "-empty",
                        "query=" + clipForLog(query)
                                + ", source=" + source.alias());
            }
        } catch (Exception ex) {
            if (fallbackLabel != null && !fallbackLabel.isBlank()) {
                LOGGER.log(Level.WARNING, "news_search.source-decision stage=" + stagePrefix + "-error query="
                        + clipForLog(query) + ", fallback=" + fallbackLabel, ex);
            } else {
                LOGGER.log(Level.WARNING, "Search fallback failed", ex);
            }
        }
        return List.of();
    }

    private KrFetchResult fetch36KrIfNeeded(String query,
                                            SourceSelection sourceSelection,
                                            FetchPlan fetchPlan,
                                            boolean shouldAttempt) {
        if (!shouldAttempt
                || fetchPlan.customSelected()
                || sourceSelection == null
                || sourceSelection.mode() == null
                || !sourceSelection.mode().includes36Kr()) {
            return KrFetchResult.notAttempted();
        }
        try {
            List<NewsItem> krItems = parseFeed(newsFeedFetcher.fetch(krFeedUrl, httpTimeoutMs), "36kr");
            List<NewsItem> filtered = filterItemsByQuery(krItems, query);
            if (!filtered.isEmpty()) {
                logSourceDecision("36kr-hit",
                        "query=" + clipForLog(query)
                                + ", items=" + filtered.size());
            } else {
                logSourceDecision("36kr-empty",
                        "query=" + clipForLog(query));
            }
            return new KrFetchResult(filtered, true, false);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "36kr feed fetch failed", ex);
            return new KrFetchResult(List.of(), true, true);
        }
    }

    private boolean shouldTryConfiguredFallback(SourceSelection sourceSelection, FetchPlan fetchPlan) {
        return fetchPlan.customSelected()
                || (sourceSelection != null && sourceSelection.mode().includesSerper())
                || serperEnabled
                || !searchSources.isEmpty();
    }

    private List<NewsItem> finalizeFetchedItems(String query, int limit, SortMode sortMode, List<NewsItem> merged) {
        LinkedHashMap<String, NewsItem> dedup = new LinkedHashMap<>();
        List<String> terms = splitQueryTerms(query);
        merged.stream()
                .sorted(buildSortComparator(sortMode, terms))
                .forEach(item -> dedup.putIfAbsent(normalize(item.title()) + "|" + normalize(item.link()), item));

        List<NewsItem> result = new ArrayList<>(dedup.values());
        logSourceDecision("final",
                "query=" + clipForLog(query)
                        + ", items=" + result.size()
                        + ", topSources=" + summarizeSources(result));
        if (result.size() > limit) {
            return List.copyOf(result.subList(0, limit));
        }
        return List.copyOf(result);
    }

    private void logSourceDecision(String stage, String details) {
        LOGGER.info("news_search.source-decision stage=" + stage + " " + details);
    }

    private String clipForLog(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').trim();
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80) + "...";
    }

    private String summarizeSources(List<NewsItem> items) {
        if (items == null || items.isEmpty()) {
            return "-";
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        for (NewsItem item : items) {
            if (item == null || item.source() == null || item.source().isBlank()) {
                continue;
            }
            sources.add(item.source().trim());
            if (sources.size() >= 3) {
                break;
            }
        }
        return sources.isEmpty() ? "-" : String.join("+", sources);
    }

    private Comparator<NewsItem> buildSortComparator(SortMode sortMode, List<String> terms) {
        Comparator<NewsItem> latest = Comparator.comparing(NewsItem::publishedAt).reversed();
        if (sortMode != SortMode.RELEVANCE || terms.isEmpty()) {
            return latest;
        }
        return Comparator
                .comparingInt((NewsItem item) -> relevanceScore(item, terms))
                .reversed()
                .thenComparing(NewsItem::publishedAt, Comparator.reverseOrder());
    }

    private int relevanceScore(NewsItem item, List<String> terms) {
        String title = normalize(item.title());
        String summary = normalize(item.summary());
        int score = 0;
        for (String term : terms) {
            if (title.contains(term)) {
                score += 4;
            }
            if (summary.contains(term)) {
                score += 2;
            }
        }
        return score;
    }

    private String describeSource(SourceSelection sourceSelection, List<NewsItem> items) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        if (items != null) {
            for (NewsItem item : items) {
                String source = item.source() == null ? "" : item.source().trim();
                if (!source.isBlank()) {
                    sources.add(source);
                }
            }
        }
        if (!sources.isEmpty()) {
            return String.join(" + ", sources);
        }
        if (sourceSelection != null && sourceSelection.alias() != null && !sourceSelection.alias().isBlank()) {
            return sourceSelection.alias();
        }
        return switch (sourceSelection == null ? SourceMode.ALL : sourceSelection.mode()) {
            case KR36 -> "36kr";
            case SERPER -> "Serper";
            case SERPAPI -> "SerpApi";
            case ALL -> "36kr + Serper";
        };
    }

    private List<NewsItem> filterItemsByQuery(List<NewsItem> sourceItems, String query) {
        List<String> terms = splitQueryTerms(query);
        if (terms.isEmpty()) {
            return sourceItems;
        }
        List<NewsItem> matched = new ArrayList<>();
        for (NewsItem item : sourceItems) {
            String haystack = normalize(item.title() + " " + item.summary());
            for (String term : terms) {
                if (haystack.contains(term)) {
                    matched.add(item);
                    break;
                }
            }
        }
        return matched.isEmpty() ? sourceItems : matched;
    }

    private List<String> splitQueryTerms(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        String segmented = normalized.replaceAll("(?<=[\\p{IsHan}])(?=[\\p{L}\\p{N}])|(?<=[\\p{L}\\p{N}])(?=[\\p{IsHan}])", " ");
        String[] parts = segmented.split("\\s+");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        return List.copyOf(terms);
    }

    private List<SearchSourceConfig> configuredSearchSources(String selectedAlias) {
        if (!searchSources.isEmpty()) {
            if (selectedAlias == null || selectedAlias.isBlank()) {
                return searchSources;
            }
            List<SearchSourceConfig> filtered = searchSources.stream()
                    .filter(source -> selectedAlias.equalsIgnoreCase(source.alias()))
                    .toList();
            return filtered.isEmpty() ? searchSources : filtered;
        }
        if (!serperEnabled && serperApiKey.isBlank() && serperNewsUrl.isBlank() && serperSearchUrl.isBlank()) {
            return List.of();
        }
        SearchSourceConfig fallback = SearchSourceConfig.serper(serperSearchUrl, serperNewsUrl, serperApiKey);
        return firstNonBlank(fallback.resolvedNewsUrl(), fallback.resolvedSearchUrl()).isBlank() ? List.of() : List.of(fallback);
    }

    private List<NewsItem> fetchSearchProvider(SearchSourceConfig source, String query) throws Exception {
        if (source == null) {
            return List.of();
        }
        List<SearchResultItem> items = searchProviderChain.search(source, new SearchRequest(query, httpTimeoutMs));
        if (items.isEmpty()) {
            return List.of();
        }
        List<NewsItem> labeled = new ArrayList<>();
        for (SearchResultItem item : items) {
            labeled.add(new NewsItem(item.title(), item.link(), item.summary(), item.publishedAt(), source.alias()));
        }
        return labeled;
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

    private SummaryBundle summarize(String query, List<NewsItem> items, SkillContext context) {
        if (!summaryEnabled || llmClient == null) {
            return fallbackSummaryBundle(query, items, context);
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是新闻摘要助手，请结合用户上下文和新闻列表，用中文输出严格 JSON。")
                .append("JSON schema: {\"theme\":\"...\",\"summary\":\"...\",\"contextBrief\":\"...\",\"hotKeywords\":[\"...\"]}. ")
                .append("要求：summary 控制在 2-3 句；contextBrief 用 1 句说明当前关注点；hotKeywords 返回 3-6 个热点词；不要输出 JSON 以外内容；不要写任何开场白、寒暄、致歉、确认、等待或“我正在搜索/已收到/请稍等”类句子。\n")
                .append("关键词: ").append(query).append("\n");
        String contextHint = summarizeContextInput(context);
        if (!contextHint.isBlank()) {
            prompt.append("用户上下文: ").append(contextHint).append("\n");
        }
        for (int i = 0; i < items.size(); i++) {
            NewsItem item = items.get(i);
            prompt.append(i + 1)
                    .append(". ")
                    .append(item.title())
                    .append(" [")
                    .append(item.source())
                    .append("]\n");
        }
        try {
            String reply = llmClient.generateResponse(prompt.toString(), buildLlmContext(context));
            SummaryBundle parsed = parseSummaryBundle(reply, query, items, context);
            if (parsed != null) {
                return parsed;
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "news_search summary LLM failed, fallback to heuristic summary", ex);
        }
        return fallbackSummaryBundle(query, items, context);
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("routeStage", "skill-postprocess");
        llmContext.put("channel", name());
        llmContext.put("llmProvider", summaryProvider);
        if (!summaryPreset.isBlank()) {
            llmContext.put("llmPreset", summaryPreset);
        }
        llmContext.put("model", summaryModel);
        llmContext.put("maxTokens", summaryMaxTokens);
        return llmContext;
    }

    private SummaryBundle fallbackSummaryBundle(String query, List<NewsItem> items, SkillContext context) {
        AtomicInteger krCount = new AtomicInteger();
        AtomicInteger serperCount = new AtomicInteger();
        items.forEach(item -> {
            if ("36kr".equals(item.source())) {
                krCount.incrementAndGet();
            }
            if ("Serper".equalsIgnoreCase(item.source()) || "serper".equalsIgnoreCase(item.source())) {
                serperCount.incrementAndGet();
            }
        });
        return new SummaryBundle(
                query + " 相关新闻共 " + items.size() + " 条，36kr " + krCount.get() + " 条，Serper " + serperCount.get() + " 条。",
                buildFallbackContextBrief(query, context, items),
                extractHotKeywords(query, items),
                buildFallbackTheme(query, items)
        );
    }

    private SummaryBundle parseSummaryBundle(String rawReply, String query, List<NewsItem> items, SkillContext context) {
        if (rawReply == null || rawReply.isBlank()) {
            return null;
        }
        try {
            String json = extractJsonBody(rawReply);
            if (json == null || json.isBlank()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            String summary = asTrimmedText(payload.get("summary"));
            String contextBrief = asTrimmedText(payload.get("contextBrief"));
            String theme = asTrimmedText(payload.get("theme"));
            List<String> hotKeywords = normalizeHotKeywords(payload.get("hotKeywords"), query, items);
            if (summary.isBlank()) {
                return null;
            }
            if (contextBrief.isBlank()) {
                contextBrief = buildFallbackContextBrief(query, context, items);
            }
            if (theme.isBlank()) {
                theme = buildFallbackTheme(query, items);
            }
            if (hotKeywords.isEmpty()) {
                hotKeywords = extractHotKeywords(query, items);
            }
            return new SummaryBundle(summary, contextBrief, hotKeywords, theme);
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "news_search summary JSON parse failed", ex);
            return null;
        }
    }

    private String summarizeContextInput(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendContextField(builder, context.attributes().get("originalInput"));
        appendContextField(builder, context.attributes().get("memoryContext"));
        String merged = builder.toString().trim();
        if (merged.length() <= 320) {
            return merged;
        }
        return merged.substring(0, 320) + "...";
    }

    private void appendContextField(StringBuilder builder, Object raw) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(cleanupText(text));
    }

    private List<String> normalizeHotKeywords(Object rawKeywords, String query, List<NewsItem> items) {
        if (!(rawKeywords instanceof List<?> values)) {
            return extractHotKeywords(query, items);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (Object value : values) {
            String keyword = asTrimmedText(value);
            if (!keyword.isBlank()) {
                normalized.add(keyword);
            }
            if (normalized.size() >= 6) {
                break;
            }
        }
        if (normalized.isEmpty()) {
            return extractHotKeywords(query, items);
        }
        return List.copyOf(normalized);
    }

    private List<String> extractHotKeywords(String query, List<NewsItem> items) {
        LinkedHashMap<String, Integer> scores = new LinkedHashMap<>();
        for (String queryTerm : splitQueryTerms(query)) {
            scores.merge(queryTerm, 4, Integer::sum);
        }
        for (NewsItem item : items) {
            String haystack = cleanupText(item.title() + " " + item.summary()).toLowerCase(Locale.ROOT);
            for (String token : haystack.split("[^\\p{L}\\p{N}#._-]+")) {
                String normalized = token == null ? "" : token.trim();
                if (!isHotKeywordCandidate(normalized)) {
                    continue;
                }
                scores.merge(normalized, 1, Integer::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .limit(6)
                .toList();
    }

    private boolean isHotKeywordCandidate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (HOT_KEYWORD_STOP_WORDS.contains(token)) {
            return false;
        }
        if (token.length() < 2) {
            return false;
        }
        return token.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private String buildFallbackContextBrief(String query, SkillContext context, List<NewsItem> items) {
        String contextHint = summarizeContextInput(context);
        if (!contextHint.isBlank()) {
            return "当前关注点围绕“" + query + "”，并结合已有上下文做了新闻聚合整理。";
        }
        if (!items.isEmpty()) {
            return "当前关注点围绕“" + query + "”，已按近期新闻热度整理重点条目。";
        }
        return "当前关注点围绕“" + query + "”。";
    }

    private String buildFallbackTheme(String query, List<NewsItem> items) {
        if (!items.isEmpty()) {
            return query + " 热点追踪";
        }
        return query;
    }

    private String asTrimmedText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? "" : text;
    }

    private String extractJsonBody(String raw) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(raw);
        return matcher.find() ? matcher.group() : null;
    }

    private String resolveQuery(SkillContext context) {
        if (context.attributes() != null) {
            Object query = context.attributes().get("query");
            if (query != null && !String.valueOf(query).isBlank()) {
                return String.valueOf(query).trim();
            }
            Object keyword = context.attributes().get("keyword");
            if (keyword != null && !String.valueOf(keyword).isBlank()) {
                return String.valueOf(keyword).trim();
            }
        }
        String input = context.input() == null ? "" : context.input().trim();
        String normalized = input.replaceFirst("(?i)^news[_ ]search", "").trim();
        String candidate = normalized.isBlank() ? input : normalized;
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

    private SourceSelection resolveSource(SkillContext context) {
        String source = contextAttributeText(context, "source");
        boolean explicitlySpecified = source != null && !source.isBlank();
        if (source == null || source.isBlank()) {
            source = extractFromInput(context, SOURCE_PATTERN);
            explicitlySpecified = source != null && !source.isBlank();
        }
        if (source == null || source.isBlank()) {
            String normalizedInput = normalize(context.input());
            if (containsAny(normalizedInput, "36kr", "36氪")) {
                source = "36kr";
                explicitlySpecified = true;
            } else if (containsAny(normalizedInput, "serper")) {
                source = "serper";
                explicitlySpecified = true;
            }
        }
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        SourceMode mode = SourceMode.fromValue(normalized);
        String alias = resolveConfiguredSourceAlias(normalized);
        logSourceDecision("resolve",
                "requested=" + (normalized.isBlank() ? "-" : normalized)
                        + ", resolvedMode=" + mode.cacheKey()
                        + ", resolvedAlias=" + (alias.isBlank() ? "-" : alias)
                        + ", explicit=" + explicitlySpecified);
        if (!alias.isBlank()) {
            return new SourceSelection(mode, alias, explicitlySpecified);
        }
        return new SourceSelection(mode, "", explicitlySpecified);
    }

    private String resolveConfiguredSourceAlias(String source) {
        if (source == null || source.isBlank() || searchSources.isEmpty()) {
            return "";
        }
        for (SearchSourceConfig config : searchSources) {
            if (config != null && source.equalsIgnoreCase(config.alias())) {
                return config.alias();
            }
        }
        return "";
    }

    private SortMode resolveSort(SkillContext context) {
        String sort = contextAttributeText(context, "sort");
        if (sort == null || sort.isBlank()) {
            sort = extractFromInput(context, SORT_PATTERN);
        }
        if (sort == null || sort.isBlank()) {
            String normalizedInput = normalize(context.input());
            if (containsAny(normalizedInput, "相关度", "按相关", "最相关", "relevance")) {
                sort = "relevance";
            } else if (containsAny(normalizedInput, "最新", "最近", "latest")) {
                sort = "latest";
            }
        }
        return SortMode.fromValue(sort);
    }

    private int resolveLimit(SkillContext context) {
        String fromAttribute = contextAttributeText(context, "limit");
        Integer parsedAttr = parseFlexibleNumber(fromAttribute);
        if (parsedAttr != null && parsedAttr > 0) {
            return parsedAttr;
        }
        Integer fromLimitParam = parseFlexibleNumber(extractFromInput(context, LIMIT_PARAM_PATTERN));
        if (fromLimitParam != null && fromLimitParam > 0) {
            return fromLimitParam;
        }
        String input = context.input() == null ? "" : context.input();
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
        return maxItems;
    }

    private String extractFromInput(SkillContext context, Pattern pattern) {
        String input = context.input() == null ? "" : context.input();
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private String contextAttributeText(SkillContext context, String key) {
        if (context.attributes() == null || !context.attributes().containsKey(key)) {
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
        int unitIndex = normalized.indexOf(unitChar);
        if (unitIndex < 0) {
            return null;
        }
        String headPart = normalized.substring(0, unitIndex);
        String tailPart = normalized.substring(unitIndex + 1);

        Integer head = headPart.isBlank() ? 1 : parseSimpleChineseNumber(headPart);
        if (head == null) {
            return null;
        }
        if (tailPart.isBlank()) {
            return head * unitValue;
        }
        Integer tail = parseSimpleChineseNumber(tailPart);
        return tail == null ? null : head * unitValue + tail;
    }

    private Integer chineseDigit(char c) {
        return switch (c) {
            case '零' -> 0;
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

    private List<NewsItem> parseFeed(String xml, String source) throws Exception {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));
        document.getDocumentElement().normalize();

        List<NewsItem> items = new ArrayList<>();
        items.addAll(parseRssItems(document, source));
        items.addAll(parseAtomEntries(document, source));
        return items;
    }

    private List<NewsItem> parseRssItems(Document document, String source) {
        NodeList nodes = document.getElementsByTagName("item");
        List<NewsItem> results = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String title = textByTag(element, "title");
            String link = textByTag(element, "link");
            String summary = textByTag(element, "description");
            String published = textByTag(element, "pubDate");
            if (title.isBlank() || link.isBlank()) {
                continue;
            }
            results.add(new NewsItem(
                    TitleCleaner.cleanTitle(cleanupText(title)),
                    link.trim(),
                    cleanupText(summary),
                    parseTime(published),
                    source
            ));
        }
        return results;
    }

    private List<NewsItem> parseAtomEntries(Document document, String source) {
        NodeList nodes = document.getElementsByTagName("entry");
        List<NewsItem> results = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String title = textByTag(element, "title");
            String summary = textByTag(element, "summary");
            if (summary.isBlank()) {
                summary = textByTag(element, "content");
            }
            String published = textByTag(element, "published");
            if (published.isBlank()) {
                published = textByTag(element, "updated");
            }
            String link = extractAtomLink(element);
            if (title.isBlank() || link.isBlank()) {
                continue;
            }
            results.add(new NewsItem(
                    TitleCleaner.cleanTitle(cleanupText(title)),
                    link.trim(),
                    cleanupText(summary),
                    parseTime(published),
                    source
            ));
        }
        return results;
    }

    private String extractAtomLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            Node node = links.item(i);
            if (!(node instanceof Element linkElement)) {
                continue;
            }
            String href = linkElement.getAttribute("href");
            if (href != null && !href.isBlank()) {
                return href;
            }
            String text = linkElement.getTextContent();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String textByTag(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.EPOCH;
        }
        String text = raw.trim();
        try {
            return ZonedDateTime.parse(text, RFC1123).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            return Instant.EPOCH;
        }
    }

    private String cleanupText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String withoutTags = value.replaceAll("<[^>]+>", " ");
        return withoutTags.replaceAll("\\s+", " ").trim();
    }

    private void cleanupExpiredCache(long now) {
        if (cache.size() > cacheMaxEntries) {
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
            if (cache.size() > cacheMaxEntries) {
                cache.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(CacheEntry::expiresAtMs)))
                        .limit(cache.size() - cacheMaxEntries)
                        .map(Map.Entry::getKey)
                        .toList()
                        .forEach(cache::remove);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null || text.isBlank() || terms == null || terms.length == 0) {
            return false;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && text.contains(normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

    private void trySetFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
            // Keep parser compatible across JDK implementations.
        }
    }

    private record NewsItem(String title, String link, String summary, Instant publishedAt, String source) {
    }

    private record SummaryBundle(String summary, String contextBrief, List<String> hotKeywords, String theme) {
    }

    private record CacheEntry(List<NewsItem> items, SummaryBundle summaryBundle, long expiresAtMs) {
    }

    private record FetchPlan(SourceMode mode,
                             String selectedAlias,
                             boolean explicitlySpecified,
                             boolean customSelected,
                             boolean preferConfiguredSources) {
    }

    private record KrFetchResult(List<NewsItem> items, boolean attempted, boolean failed) {
        private static KrFetchResult notAttempted() {
            return new KrFetchResult(List.of(), false, false);
        }
    }

    private enum SourceMode {
        ALL,
        KR36,
        SERPER,
        SERPAPI;

        static SourceMode fromValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return ALL;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "36kr", "kr", "kr36" -> KR36;
                case "serper" -> SERPER;
                case "serpapi" -> SERPAPI;
                default -> ALL;
            };
        }

        boolean includes36Kr() {
            return this == ALL || this == KR36;
        }

        boolean includesSerper() {
            return this == ALL || this == SERPER || this == SERPAPI;
        }

        String cacheKey() {
            return switch (this) {
                case KR36 -> "36kr";
                case SERPER -> "serper";
                case SERPAPI -> "serpapi";
                case ALL -> "all";
            };
        }
    }

    private record SourceSelection(SourceMode mode, String alias, boolean explicitlySpecified) {
        private String cacheKey() {
            if (alias != null && !alias.isBlank()) {
                return alias.trim().toLowerCase(Locale.ROOT);
            }
            return mode == null ? "all" : mode.cacheKey();
        }
    }

    private enum SortMode {
        LATEST,
        RELEVANCE;

        static SortMode fromValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return LATEST;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return "relevance".equals(normalized) ? RELEVANCE : LATEST;
        }

        String cacheKey() {
            return this == RELEVANCE ? "relevance" : "latest";
        }

        String displayName() {
            return this == RELEVANCE ? "relevance" : "latest";
        }
    }

    interface NewsFeedFetcher {
        String fetch(String url, int timeoutMs) throws Exception;
    }

    static class DefaultNewsFeedFetcher implements NewsFeedFetcher {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String fetch(String url, int timeoutMs) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("User-Agent", "MindOS-NewsSearch/1.0 (+https://github.com/zhongbo/mindos)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("http_" + response.statusCode());
            }
            return response.body();
        }
    }
}

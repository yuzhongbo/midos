package com.zhongbo.mindos.assistant.api.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.LlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Service
public class NewsPushService {

    private static final Logger LOGGER = Logger.getLogger(NewsPushService.class.getName());
    private static final DateTimeFormatter TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.CHINA);

    private final HttpClient httpClient;
    private final NewsDeliveryClient newsDeliveryClient;
    private final LlmClient llmClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<NewsPushConfig> configRef;
    private final Duration requestTimeout;

    @Autowired
    public NewsPushService(@Value("${mindos.news.enabled:false}") boolean enabled,
                           @Value("${mindos.news.sources:https://feeds.bbci.co.uk/news/world/rss.xml}") String sourcesCsv,
                           @Value("${mindos.news.max-items:6}") int maxItems,
                           @Value("${mindos.news.push.cron:0 0 9 * * *}") String cron,
                           @Value("${mindos.news.push.timezone:Asia/Shanghai}") String timezone,
                           @Value("${mindos.news.push.dingtalk.session-webhook:}") String sessionWebhook,
                           @Value("${mindos.news.push.dingtalk.open-conversation-id:}") String openConversationId,
                           @Value("${mindos.news.push.dingtalk.sender-id:news-bot}") String senderId,
                           @Value("${mindos.news.message.max-chars:1800}") int messageMaxChars,
                           @Value("${mindos.news.http.connect-timeout-ms:3000}") long connectTimeoutMs,
                           @Value("${mindos.news.http.request-timeout-ms:5000}") long requestTimeoutMs,
                           NewsDeliveryClient newsDeliveryClient,
                           LlmClient llmClient) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1000L, connectTimeoutMs)))
                        .build(),
                newsDeliveryClient,
                llmClient,
                Clock.systemDefaultZone(),
                Duration.ofMillis(Math.max(1000L, requestTimeoutMs)),
                new NewsPushConfig(
                        enabled,
                        parseSources(sourcesCsv),
                        maxItems,
                        cron,
                        timezone,
                        sessionWebhook,
                        openConversationId,
                        senderId,
                        messageMaxChars
                ).normalize());
    }

    NewsPushService(HttpClient httpClient,
                    NewsDeliveryClient newsDeliveryClient,
                    LlmClient llmClient,
                    Clock clock,
                    Duration requestTimeout,
                    NewsPushConfig initialConfig) {
        this.httpClient = httpClient;
        this.newsDeliveryClient = newsDeliveryClient;
        this.llmClient = llmClient;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        this.configRef = new AtomicReference<>(initialConfig == null
                ? new NewsPushConfig(false, List.of(), 5, "0 0 9 * * *", "Asia/Shanghai", "", "", "news-bot", 1800)
                : initialConfig.normalize());
    }

    public NewsPushConfig getConfig() {
        return configRef.get();
    }

    public NewsPushConfig updateConfig(NewsPushConfig newConfig) {
        NewsPushConfig normalized = (newConfig == null ? getConfig() : newConfig).normalize();
        configRef.set(normalized);
        return normalized;
    }

    public Optional<ZonedDateTime> nextRunTime() {
        try {
            NewsPushConfig cfg = getConfig();
            if (!cfg.enabled()) {
                return Optional.empty();
            }
            if (cfg.sources() == null || cfg.sources().isEmpty()) {
                return Optional.empty();
            }
            ZonedDateTime now = ZonedDateTime.now(cfg.zoneIdOrDefault());
            ZonedDateTime next = cfg.cronExpression().next(now);
            return Optional.ofNullable(next);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "NewsPushService: failed to compute next run time", ex);
            return Optional.empty();
        }
    }

    public NewsPushResult pushOnce() {
        NewsPushConfig cfg = getConfig();
        Instant now = clock.instant();
        if (!cfg.enabled()) {
            return new NewsPushResult(false, 0, 0, "", "news push disabled", "", "", now, List.of());
        }
        List<NewsItem> items = fetchNews(cfg);
        if (items.isEmpty()) {
            return new NewsPushResult(false, 0, 0, "", "no news fetched", "", "", now, items);
        }
        String summary = summarize(items, cfg);
        String message = buildMessage(items, summary, cfg, now);
        boolean delivered = newsDeliveryClient.deliver(message, cfg);
        return new NewsPushResult(delivered, items.size(), Math.min(items.size(), cfg.maxItems()),
                resolveChannel(cfg), delivered ? "" : "delivery_failed", summary, message, now, items);
    }

    private List<NewsItem> fetchNews(NewsPushConfig cfg) {
        List<NewsItem> all = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        for (String source : cfg.sources()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                        .timeout(requestTimeout)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2 || response.body() == null || response.body().isBlank()) {
                    LOGGER.warning("NewsPushService: failed to fetch " + source + ", status=" + response.statusCode());
                    continue;
                }
                all.addAll(parseFeed(response.body(), source));
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "NewsPushService: fetch failed for " + source, ex);
            }
            if (all.size() >= cfg.maxItems()) {
                break;
            }
        }
        List<NewsItem> unique = new ArrayList<>();
        for (NewsItem item : all) {
            String titleKey = normalizeTitle(item.title());
            if (titleKey.isBlank() || seenTitles.contains(titleKey)) {
                continue;
            }
            seenTitles.add(titleKey);
            unique.add(item);
            if (unique.size() >= cfg.maxItems()) {
                break;
            }
        }
        return unique;
    }

    private List<NewsItem> parseFeed(String body, String source) {
        List<NewsItem> items = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
            NodeList nodeList = document.getElementsByTagName("item");
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                String title = textOf(element, "title");
                String link = textOf(element, "link");
                String description = stripTags(textOf(element, "description"));
                Instant publishedAt = parsePubDate(textOf(element, "pubDate"));
                items.add(new NewsItem(title, link, description, publishedAt, source));
            }
        } catch (Exception ex) {
            LOGGER.log(Level.INFO, "NewsPushService: XML parse failed, try JSON fallback", ex);
            items.addAll(parseJsonFeed(body, source));
        }
        return items;
    }

    private List<NewsItem> parseJsonFeed(String body, String source) {
        List<NewsItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode entries = root.path("items").isMissingNode() ? root.path("entries") : root.path("items");
            if (!entries.isArray()) {
                return items;
            }
            for (JsonNode item : entries) {
                String title = item.path("title").asText("");
                String link = item.path("url").asText(item.path("link").asText(""));
                String description = stripTags(item.path("summary").asText(item.path("description").asText("")));
                Instant publishedAt = parsePubDate(item.path("published").asText(item.path("pubDate").asText("")));
                items.add(new NewsItem(title, link, description, publishedAt, source));
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "NewsPushService: JSON feed parse failed", ex);
        }
        return items;
    }

    private Instant parsePubDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private String textOf(Element element, String tag) {
        NodeList list = element.getElementsByTagName(tag);
        if (list == null || list.getLength() == 0) {
            return "";
        }
        return list.item(0).getTextContent();
    }

    private String summarize(List<NewsItem> items, NewsPushConfig cfg) {
        String prompt = buildPrompt(items, cfg);
        try {
            String reply = llmClient.generateResponse(prompt, java.util.Map.of("stage", "news-summary", "itemCount", items.size()));
            if (reply != null && !reply.trim().isBlank()) {
                return reply.trim();
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "NewsPushService: LLM summary failed, fallback", ex);
        }
        return fallbackSummary(items);
    }

    private String buildPrompt(List<NewsItem> items, NewsPushConfig cfg) {
        StringBuilder builder = new StringBuilder();
        builder.append("请用简洁的中文总结以下最新新闻，输出3-6条要点，并概括整体走势，不要重复原标题：");
        builder.append("\n时间：").append(TITLE_TIME_FORMATTER.format(ZonedDateTime.ofInstant(clock.instant(), cfg.zoneIdOrDefault())));
        builder.append("\n新闻列表：");
        int idx = 1;
        for (NewsItem item : items) {
            builder.append("\n").append(idx++).append(". ").append(clean(item.title()));
            if (StringUtils.hasText(item.summary())) {
                builder.append(" —— ").append(trimIfNeeded(clean(item.summary()), 200));
            }
        }
        return builder.toString();
    }

    private String fallbackSummary(List<NewsItem> items) {
        StringBuilder builder = new StringBuilder("最新要闻摘要：");
        int idx = 1;
        for (NewsItem item : items) {
            builder.append("\n").append(idx++).append(". ").append(clean(item.title()));
            if (idx > 6) {
                break;
            }
        }
        return builder.toString();
    }

    private String buildMessage(List<NewsItem> items, String summary, NewsPushConfig cfg, Instant now) {
        StringBuilder builder = new StringBuilder();
        builder.append("新闻简报 ").append(TITLE_TIME_FORMATTER.format(ZonedDateTime.ofInstant(now, cfg.zoneIdOrDefault())));
        if (StringUtils.hasText(summary)) {
            builder.append("\n").append(summary.trim());
        }
        builder.append("\n\n精选资讯：");
        int idx = 1;
        for (NewsItem item : items) {
            builder.append("\n").append(idx++).append(". ").append(clean(item.title()));
            if (StringUtils.hasText(item.link())) {
                builder.append(" ").append(item.link().trim());
            }
        }
        return trimIfNeeded(builder.toString(), cfg.messageMaxChars());
    }

    private String resolveChannel(NewsPushConfig cfg) {
        if (cfg.sessionWebhook() != null && !cfg.sessionWebhook().isBlank()) {
            return "dingtalk-session-webhook";
        }
        if (cfg.openConversationId() != null && !cfg.openConversationId().isBlank()) {
            return "dingtalk-openapi";
        }
        return "";
    }

    private String trimIfNeeded(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxChars - 10)).trim() + " ...";
    }

    private static List<String> parseSources(String csv) {
        List<String> list = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (part != null && !part.trim().isBlank()) {
                    list.add(part.trim());
                }
            }
        }
        return list;
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String stripTags(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}

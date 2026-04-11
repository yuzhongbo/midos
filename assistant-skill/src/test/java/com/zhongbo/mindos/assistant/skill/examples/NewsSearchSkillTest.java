package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsSearchSkillTest {

    @Test
    void shouldAggregate36KrAndUseLlmSummary() {
        AtomicReference<Map<String, Object>> capturedContext = new AtomicReference<>();
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    capturedPrompt.set(prompt);
                    capturedContext.set(context);
                    return "{\"theme\":\"AI硬件\",\"summary\":\"产业继续升温，AI 芯片与创业投资都在加速。\",\"contextBrief\":\"当前上下文更关注 AI 芯片与产业动态。\",\"hotKeywords\":[\"AI芯片\",\"创业投资\",\"产业链\"]}";
                },
                (url, timeoutMs) -> krFeed(),
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                6,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        SkillResult result = skill.run(new SkillContext("u1", "news_search AI 芯片", Map.of(
                "memoryContext", "用户最近一直关注芯片和创业投资。"
        )));

        assertTrue(result.success());
        assertTrue(result.output().contains("[news_search]"));
        assertTrue(result.output().contains("主题: AI硬件"));
        assertTrue(result.output().contains("热点关键词: AI芯片、创业投资、产业链"));
        assertTrue(result.output().contains("上下文总结: 当前上下文更关注 AI 芯片与产业动态。"));
        assertTrue(result.output().contains("36Kr AI 创业观察"));
        assertTrue(capturedPrompt.get().contains("用户上下文: 用户最近一直关注芯片和创业投资。"));
        assertEquals("local", capturedContext.get().get("llmProvider"));
        assertEquals("gemma3:1b-it-q4_K_M", capturedContext.get().get("model"));
    }

    @Test
    void shouldUseCacheForSameQueryWithinTtlAndReuseSummary() {
        AtomicInteger fetchCount = new AtomicInteger();
        AtomicInteger llmCallCount = new AtomicInteger();
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    llmCallCount.incrementAndGet();
                    return "{\"theme\":\"AI观察\",\"summary\":\"摘要\",\"contextBrief\":\"上下文已整理。\",\"hotKeywords\":[\"AI\",\"芯片\"]}";
                },
                (url, timeoutMs) -> {
                    fetchCount.incrementAndGet();
                    return krFeed();
                },
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                5,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        skill.run(new SkillContext("u1", "news_search AI", Map.of()));
        skill.run(new SkillContext("u1", "news_search AI", Map.of()));

        assertEquals(1, fetchCount.get(), "cache命中时第二次不应再次拉取feed");
        assertEquals(1, llmCallCount.get(), "缓存命中时第二次不应再次生成摘要");
    }

    @Test
    void shouldFallbackToHeuristicSummaryWhenLlmFails() {
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    throw new RuntimeException("llm down");
                },
                (url, timeoutMs) -> krFeed(),
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                5,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        SkillResult result = skill.run(new SkillContext("u1", "", Map.of("query", "AI")));

        assertTrue(result.success());
        assertTrue(result.output().contains("摘要: AI 相关新闻共 1 条"));
        assertTrue(result.output().contains("热点关键词:"));
        assertTrue(result.output().contains("上下文总结:"));
        assertTrue(result.output().contains("36kr 1 条"));
    }

    @Test
    void shouldSupportSourceSortAndNaturalLanguageLimit() {
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                (url, timeoutMs) -> """
                        <rss><channel>
                          <item>
                            <title>区块链市场早报</title>
                            <link>https://36kr.example/chain</link>
                            <description>行情回顾</description>
                            <pubDate>Fri, 03 Apr 2026 12:00:00 GMT</pubDate>
                          </item>
                          <item>
                            <title>AI 芯片设计突破</title>
                            <link>https://36kr.example/ai-chip</link>
                            <description>算力与制程协同推进</description>
                            <pubDate>Fri, 03 Apr 2026 10:00:00 GMT</pubDate>
                          </item>
                        </channel></rss>
                        """,
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                8,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        SkillResult result = skill.run(new SkillContext("u1", "news_search AI source=36kr sort=relevance 前一条", Map.of()));

        assertTrue(result.success());
        assertTrue(result.output().contains("来源: 36kr"));
        assertTrue(result.output().contains("排序: relevance"));
        assertTrue(result.output().contains("AI 芯片设计突破"));
        assertFalse(result.output().contains("区块链市场早报"));
    }

    @Test
    void shouldInferSourceAndLimitFromChineseMixedInput() {
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"融资\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"融资\",\"AI\"]}",
                (url, timeoutMs) -> """
                        <rss><channel>
                          <item>
                            <title>36Kr AI 融资周报</title>
                            <link>https://36kr.example/ai-financing</link>
                            <description>多家 AI 公司完成新一轮融资</description>
                            <pubDate>Fri, 03 Apr 2026 11:00:00 GMT</pubDate>
                          </item>
                        </channel></rss>
                        """,
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                8,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        SkillResult result = skill.run(new SkillContext("u1", "news_search 只看36kr最新三条AI融资新闻", Map.of()));

        assertTrue(result.success());
        assertTrue(result.output().contains("关键词: AI融资"));
        assertFalse(result.output().contains("AI融资 闻"));
        assertTrue(result.output().contains("来源: 36kr"));
        assertTrue(result.output().contains("排序: latest"));
        assertTrue(result.output().contains("36Kr AI 融资周报"));
    }

    @Test
    void shouldSupportChineseNaturalNewsTrigger() {
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                (url, timeoutMs) -> krFeed(),
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                8,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        SkillRegistry registry = new SkillRegistry(List.of(skill));
        assertEquals("news_search", registry.detect("查看新闻 AI 前五条，并总结").map(found -> found.name()).orElse(""));
        assertEquals("news_search", registry.detect("最近的国际新闻").map(found -> found.name()).orElse(""));
    }

    @Test
    void shouldCleanTopicForPlainNewsRequestWithSummaryAction() {
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"国际\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"国际\"]}",
                (url, timeoutMs) -> """
                        <rss><channel>
                          <item>
                            <title>国际市场观察</title>
                            <link>https://36kr.example/global-market</link>
                            <description>国际新闻摘要</description>
                            <pubDate>Fri, 03 Apr 2026 11:00:00 GMT</pubDate>
                          </item>
                        </channel></rss>
                        """,
                true,
                "https://36kr.com/feed",
                3000,
                300,
                64,
                8,
                true,
                "local",
                "cost",
                "gemma3:1b-it-q4_K_M",
                220
        );

        SkillResult result = skill.run(new SkillContext("u1", "帮我看今天的国际新闻并总结一下", Map.of()));

        assertTrue(result.success());
        assertTrue(result.output().contains("关键词: 国际"));
        assertFalse(result.output().contains("并总结"));
        assertFalse(result.output().contains("今天的国际新闻"));
    }

    @Test
    void shouldPreferConfiguredSearchSourcesWhenNoExplicitSourceIsProvided() throws Exception {
        AtomicInteger feedFetchCount = new AtomicInteger();
        AtomicInteger searchRequestCount = new AtomicInteger();
        HttpServer server = startSearchServer(searchRequestCount, """
                {
                  "news": [
                    {
                      "title": "Serper AI 观察",
                      "link": "https://serper.example/ai-observe",
                      "snippet": "来自统一搜索源",
                      "source": "serper",
                      "publishedAt": "2026-04-03T11:00:00Z"
                    }
                  ]
                }
                """);
        try {
            String searchSources = "serper:http://localhost:" + server.getAddress().getPort() + "/search;news-url=http://localhost:"
                    + server.getAddress().getPort() + "/news;api-key=test-key";
            NewsSearchSkill skill = new NewsSearchSkill(
                    (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                    (url, timeoutMs) -> {
                        feedFetchCount.incrementAndGet();
                        return krFeed();
                    },
                    true,
                    "https://36kr.com/feed",
                    3000,
                    300,
                    64,
                    8,
                    true,
                    "local",
                    "cost",
                    "gemma3:1b-it-q4_K_M",
                    220,
                    searchSources,
                    false,
                    "",
                    "",
                    ""
            );

            SkillResult result = skill.run(new SkillContext("u1", "news_search AI", Map.of()));

            assertTrue(result.success());
            assertTrue(result.output().contains("来源:"));
            assertTrue(result.output().contains("Serper AI 观察"));
            assertEquals(1, searchRequestCount.get(), "配置了统一搜索源后默认应优先请求搜索源");
            assertEquals(0, feedFetchCount.get(), "统一搜索源成功时不应再先拉取36kr");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepExplicit36krSelectionEvenWhenConfiguredSearchSourcesExist() throws Exception {
        AtomicInteger feedFetchCount = new AtomicInteger();
        AtomicInteger searchRequestCount = new AtomicInteger();
        HttpServer server = startSearchServer(searchRequestCount, """
                {
                  "news": [
                    {
                      "title": "Serper AI 观察",
                      "link": "https://serper.example/ai-observe",
                      "snippet": "来自统一搜索源",
                      "source": "serper",
                      "publishedAt": "2026-04-03T11:00:00Z"
                    }
                  ]
                }
                """);
        try {
            String searchSources = "serper:http://localhost:" + server.getAddress().getPort() + "/search;news-url=http://localhost:"
                    + server.getAddress().getPort() + "/news;api-key=test-key";
            NewsSearchSkill skill = new NewsSearchSkill(
                    (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                    (url, timeoutMs) -> {
                        feedFetchCount.incrementAndGet();
                        return krFeed();
                    },
                    true,
                    "https://36kr.com/feed",
                    3000,
                    300,
                    64,
                    8,
                    true,
                    "local",
                    "cost",
                    "gemma3:1b-it-q4_K_M",
                    220,
                    searchSources,
                    false,
                    "",
                    "",
                    ""
            );

            SkillResult result = skill.run(new SkillContext("u1", "news_search AI source=36kr", Map.of()));

            assertTrue(result.success());
            assertTrue(result.output().contains("来源: 36kr"));
            assertTrue(result.output().contains("36Kr AI 创业观察"));
            assertEquals(1, feedFetchCount.get(), "显式指定36kr时应继续使用36kr源");
            assertEquals(0, searchRequestCount.get(), "显式指定36kr时不应请求统一搜索源");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLogConfiguredSourceDecisionWhenConfiguredSourceBecomesPrimary() throws Exception {
        AtomicInteger searchRequestCount = new AtomicInteger();
        HttpServer server = startSearchServer(searchRequestCount, """
                {
                  "news": [
                    {
                      "title": "Serper AI 观察",
                      "link": "https://serper.example/ai-observe",
                      "snippet": "来自统一搜索源",
                      "source": "serper",
                      "publishedAt": "2026-04-03T11:00:00Z"
                    }
                  ]
                }
                """);
        LogCapture capture = new LogCapture();
        Logger logger = Logger.getLogger(NewsSearchSkill.class.getName());
        logger.addHandler(capture);
        logger.setLevel(Level.INFO);
        try {
            String searchSources = "serper:http://localhost:" + server.getAddress().getPort() + "/search;news-url=http://localhost:"
                    + server.getAddress().getPort() + "/news;api-key=test-key";
            NewsSearchSkill skill = new NewsSearchSkill(
                    (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                    (url, timeoutMs) -> krFeed(),
                    true,
                    "https://36kr.com/feed",
                    3000,
                    300,
                    64,
                    8,
                    true,
                    "local",
                    "cost",
                    "gemma3:1b-it-q4_K_M",
                    220,
                    searchSources,
                    false,
                    "",
                    "",
                    ""
            );

            skill.run(new SkillContext("u1", "news_search AI", Map.of()));

            assertTrue(capture.messages().stream().anyMatch(msg -> msg.contains("stage=configured-primary-hit") && msg.contains("source=serper")));
            assertTrue(capture.messages().stream().anyMatch(msg -> msg.contains("stage=final") && msg.contains("topSources=serper")));
            assertEquals(1, searchRequestCount.get());
        } finally {
            logger.removeHandler(capture);
            server.stop(0);
        }
    }

    @Test
    void shouldSupportSerpApiConfiguredSearchSourcesForPreciseKeywordQueries() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicBoolean apiKeySeen = new AtomicBoolean(false);
        AtomicBoolean querySeen = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/news.json", exchange -> {
            requestCount.incrementAndGet();
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            if ("serpapi-test-key".equals(params.get("api_key"))) {
                apiKeySeen.set(true);
            }
            if ("OpenAI o3-mini pricing".equals(params.get("q"))) {
                querySeen.set(true);
            }
            byte[] response = ("""
                    {
                      "news_results": [
                        {
                          "title": "OpenAI o3-mini pricing update",
                          "link": "https://serpapi.example/openai-o3",
                          "snippet": "SerpApi 精准关键词命中",
                          "source": "SerpApi",
                          "publishedAt": "2026-04-03T11:00:00Z"
                        }
                      ]
                    }
                    """).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String searchSources = "serpapi:http://localhost:" + server.getAddress().getPort() + "/search.json?engine=google"
                    + ";news-url=http://localhost:" + server.getAddress().getPort() + "/news.json?engine=google_news"
                    + ";api-key=serpapi-test-key";
            NewsSearchSkill skill = new NewsSearchSkill(
                    (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                    (url, timeoutMs) -> krFeed(),
                    true,
                    "https://36kr.com/feed",
                    3000,
                    300,
                    64,
                    8,
                    true,
                    "local",
                    "cost",
                    "gemma3:1b-it-q4_K_M",
                    220,
                    searchSources,
                    false,
                    "",
                    "",
                    ""
            );

            SkillResult result = skill.run(new SkillContext("u1", "news_search OpenAI o3-mini pricing source=serpapi", Map.of()));

            assertTrue(result.success());
            assertTrue(result.output().contains("OpenAI o3-mini pricing update"));
            assertTrue(apiKeySeen.get());
            assertTrue(querySeen.get());
            assertEquals(1, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLog36krFallbackWhenConfiguredSourceReturnsNoResults() throws Exception {
        AtomicInteger feedFetchCount = new AtomicInteger();
        HttpServer server = startSearchServer(new AtomicInteger(), "{\"news\":[]}");
        LogCapture capture = new LogCapture();
        Logger logger = Logger.getLogger(NewsSearchSkill.class.getName());
        logger.addHandler(capture);
        logger.setLevel(Level.INFO);
        try {
            String searchSources = "serper:http://localhost:" + server.getAddress().getPort() + "/search;news-url=http://localhost:"
                    + server.getAddress().getPort() + "/news;api-key=test-key";
            NewsSearchSkill skill = new NewsSearchSkill(
                    (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                    (url, timeoutMs) -> {
                        feedFetchCount.incrementAndGet();
                        return krFeed();
                    },
                    true,
                    "https://36kr.com/feed",
                    3000,
                    300,
                    64,
                    8,
                    true,
                    "local",
                    "cost",
                    "gemma3:1b-it-q4_K_M",
                    220,
                    searchSources,
                    false,
                    "",
                    "",
                    ""
            );

            skill.run(new SkillContext("u1", "news_search AI", Map.of()));

            assertTrue(capture.messages().stream().anyMatch(msg -> msg.contains("stage=configured-primary-empty") && msg.contains("source=serper")));
            assertTrue(capture.messages().stream().anyMatch(msg -> msg.contains("stage=36kr-hit") && msg.contains("items=1")));
            assertEquals(1, feedFetchCount.get());
        } finally {
            logger.removeHandler(capture);
            server.stop(0);
        }
    }


    private String krFeed() {
        return """
                <rss><channel>
                  <item>
                    <title>36Kr AI 创业观察</title>
                    <link>https://36kr.example/ai-startup</link>
                    <description>AI创业公司融资情况</description>
                    <pubDate>Fri, 03 Apr 2026 11:00:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;
    }

    private HttpServer startSearchServer(AtomicInteger requestCount, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> respondJson(exchange, requestCount, body));
        server.createContext("/news", exchange -> respondJson(exchange, requestCount, body));
        server.start();
        return server;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(rawQuery.split("&"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length > 0 && !parts[0].isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        parts -> java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
                        parts -> parts.length > 1 ? java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8) : "",
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
    }

    private void respondJson(HttpExchange exchange, AtomicInteger requestCount, String body) throws IOException {
        requestCount.incrementAndGet();
        byte[] payload = body.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    private static final class LogCapture extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private List<String> messages() {
            return messages;
        }
    }
}

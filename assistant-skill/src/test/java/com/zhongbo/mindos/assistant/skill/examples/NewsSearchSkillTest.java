package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsSearchSkillTest {

    @Test
    void shouldAggregateFeedsAndUseLlmSummary() {
        AtomicReference<Map<String, Object>> capturedContext = new AtomicReference<>();
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            if (url.contains("/google/")) {
                return googleFeed();
            }
            return krFeed();
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    capturedPrompt.set(prompt);
                    capturedContext.set(context);
                    return "{\"theme\":\"AI硬件\",\"summary\":\"产业继续升温，AI 芯片与创业投资都在加速。\",\"contextBrief\":\"当前上下文更关注 AI 芯片与产业动态。\",\"hotKeywords\":[\"AI芯片\",\"创业投资\",\"产业链\"]}";
                },
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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
        assertTrue(result.output().contains("Google AI 芯片进展"));
        assertTrue(result.output().contains("36Kr AI 创业观察"));
        assertTrue(capturedPrompt.get().contains("用户上下文: 用户最近一直关注芯片和创业投资。"));
        assertEquals("local", capturedContext.get().get("llmProvider"));
        assertEquals("gemma3:1b-it-q4_K_M", capturedContext.get().get("model"));
    }

    @Test
    void shouldUseCacheForSameQueryWithinTtlAndReuseSummary() {
        AtomicInteger fetchCount = new AtomicInteger();
        AtomicInteger llmCallCount = new AtomicInteger();
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            fetchCount.incrementAndGet();
            if (url.contains("/google/")) {
                return googleFeed();
            }
            return krFeed();
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    llmCallCount.incrementAndGet();
                    return "{\"theme\":\"AI观察\",\"summary\":\"摘要\",\"contextBrief\":\"上下文已整理。\",\"hotKeywords\":[\"AI\",\"芯片\"]}";
                },
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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

        assertEquals(2, fetchCount.get(), "cache命中时第二次不应再次拉取双源feed");
        assertEquals(1, llmCallCount.get(), "缓存命中时第二次不应再次生成摘要");
    }

    @Test
    void shouldFallbackToHeuristicSummaryWhenLlmFails() {
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> url.contains("/google/") ? googleFeed() : krFeed();
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    throw new RuntimeException("llm down");
                },
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("query", "AI");
        SkillResult result = skill.run(new SkillContext("u1", "", attrs));

        assertTrue(result.success());
        assertTrue(result.output().contains("摘要: AI 相关新闻共 2 条"));
        assertTrue(result.output().contains("热点关键词:"));
        assertTrue(result.output().contains("上下文总结:"));
        assertTrue(result.output().contains("Google"));
    }

    @Test
    void shouldSupportSourceSortAndNaturalLanguageLimit() {
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            if (url.contains("/google/")) {
                return """
                        <rss><channel>
                          <item>
                            <title>区块链市场早报</title>
                            <link>https://news.example/google-chain</link>
                            <description>行情回顾</description>
                            <pubDate>Fri, 03 Apr 2026 12:00:00 GMT</pubDate>
                          </item>
                          <item>
                            <title>AI 芯片设计突破</title>
                            <link>https://news.example/google-ai-chip</link>
                            <description>算力与制程协同推进</description>
                            <pubDate>Fri, 03 Apr 2026 10:00:00 GMT</pubDate>
                          </item>
                        </channel></rss>
                        """;
            }
            return krFeed();
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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

        SkillResult result = skill.run(new SkillContext("u1", "news_search AI source=google sort=relevance 前一条", Map.of()));

        assertTrue(result.success());
        assertTrue(result.output().contains("来源: RSS 源"));
        assertTrue(result.output().contains("排序: relevance"));
        assertTrue(result.output().contains("AI 芯片设计突破"));
        assertFalse(result.output().contains("区块链市场早报"));
        assertFalse(result.output().contains("36Kr AI 创业观察"));
    }

    @Test
    void shouldSeparateCacheBySourceAndSortParameters() {
        AtomicInteger fetchCount = new AtomicInteger();
        AtomicInteger llmCallCount = new AtomicInteger();
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            fetchCount.incrementAndGet();
            if (url.contains("/google/")) {
                return googleFeed();
            }
            return krFeed();
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> {
                    llmCallCount.incrementAndGet();
                    return "{\"theme\":\"AI观察\",\"summary\":\"摘要\",\"contextBrief\":\"上下文已整理。\",\"hotKeywords\":[\"AI\",\"芯片\"]}";
                },
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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

        skill.run(new SkillContext("u1", "news_search AI", Map.of()));
        skill.run(new SkillContext("u1", "news_search AI source=google", Map.of()));

        assertEquals(3, fetchCount.get(), "all + google 应触发 2 + 1 次抓取");
        assertEquals(2, llmCallCount.get(), "source 参数变化后应使用独立摘要缓存");
    }

    @Test
    void shouldInferSourceAndLimitFromChineseMixedInput() {
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            if (url.contains("/google/")) {
                return googleFeed();
            }
            return """
                    <rss><channel>
                      <item>
                        <title>36Kr AI 融资周报</title>
                        <link>https://36kr.example/ai-financing</link>
                        <description>多家 AI 公司完成新一轮融资</description>
                        <pubDate>Fri, 03 Apr 2026 11:00:00 GMT</pubDate>
                      </item>
                    </channel></rss>
                    """;
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"融资\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"融资\",\"AI\"]}",
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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
        assertTrue(result.output().contains("来源: 36kr"));
        assertTrue(result.output().contains("排序: latest"));
        assertTrue(result.output().contains("36Kr AI 融资周报"));
        assertFalse(result.output().contains("Google AI 芯片进展"));
    }

    @Test
    void shouldInferRelevanceSortFromChineseMixedInput() {
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            if (url.contains("/google/")) {
                return """
                        <rss><channel>
                          <item>
                            <title>区块链市场快讯</title>
                            <link>https://news.example/google-chain</link>
                            <description>今日波动加大</description>
                            <pubDate>Fri, 03 Apr 2026 12:00:00 GMT</pubDate>
                          </item>
                          <item>
                            <title>AI 芯片融资提速</title>
                            <link>https://news.example/google-ai-financing</link>
                            <description>AI 赛道投资持续升温</description>
                            <pubDate>Fri, 03 Apr 2026 10:00:00 GMT</pubDate>
                          </item>
                        </channel></rss>
                        """;
            }
            return krFeed();
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"融资\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"融资\",\"AI\"]}",
                fetcher,
                true,
                "https://ai.2756online.com/google/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans",
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

        SkillResult result = skill.run(new SkillContext("u1", "news_search 只看google最相关一条AI融资新闻", Map.of()));

        assertTrue(result.success());
        assertTrue(result.output().contains("来源: RSS 源"));
        assertTrue(result.output().contains("排序: relevance"));
        assertTrue(result.output().contains("AI 芯片融资提速"));
        assertFalse(result.output().contains("区块链市场快讯"));
        assertFalse(result.output().contains("36Kr AI 创业观察"));
    }

    @Test
    void shouldSupportFixedRssUrlTemplateWithoutPlaceholder() {
        AtomicReference<String> firstFetchedUrl = new AtomicReference<>();
        NewsSearchSkill.NewsFeedFetcher fetcher = (url, timeoutMs) -> {
            firstFetchedUrl.compareAndSet(null, url);
            if ("https://www.reddit.com/r/worldnews/.rss".equals(url)) {
                return googleFeed();
            }
            return krFeed();
        };
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"国际新闻\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"国际\",\"新闻\"]}",
                fetcher,
                true,
                "https://www.reddit.com/r/worldnews/.rss",
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

        SkillResult result = skill.run(new SkillContext("u1", "news_search 国际实时新闻", Map.of()));

        assertTrue(result.success());
        assertEquals("https://www.reddit.com/r/worldnews/.rss", firstFetchedUrl.get());
    }

    @Test
    void shouldSupportChineseNaturalNewsTrigger() {
        NewsSearchSkill skill = new NewsSearchSkill(
                (prompt, context) -> "{\"theme\":\"AI\",\"summary\":\"摘要\",\"contextBrief\":\"上下文\",\"hotKeywords\":[\"AI\"]}",
                (url, timeoutMs) -> googleFeed(),
                true,
                "https://www.reddit.com/r/worldnews/.rss",
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

        assertTrue(skill.supports("查看新闻 AI 前五条，并总结"));
    }

    private String googleFeed() {
        return """
                <rss><channel>
                  <item>
                    <title>Google AI 芯片进展</title>
                    <link>https://news.example/google-ai-chip</link>
                    <description>产业链持续推进</description>
                    <pubDate>Fri, 03 Apr 2026 10:00:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """;
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
}

package com.zhongbo.mindos.assistant.skill.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultDetailAugmentorTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldPreferOfficialDocsPageOverGenericLandingPage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/landing", exchange -> {
            byte[] payload = """
                    <html>
                      <head><title>Spring 官网首页</title></head>
                      <body>
                        <main>
                          <p>欢迎来到 Spring 官网，这里可以查看所有项目和产品概览。</p>
                        </main>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/docs/restclient", exchange -> {
            byte[] payload = """
                    <html>
                      <head>
                        <title>Spring Framework RestClient Reference</title>
                        <meta name="description" content="Official documentation for Spring RestClient.">
                      </head>
                      <body>
                        <article>
                          <p>This official guide explains Spring RestClient usage, request factories, and API examples.</p>
                          <p>It is the reference documentation page developers should read first.</p>
                        </article>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        SearchResultDetailAugmentor augmentor = new SearchResultDetailAugmentor(true, 3000, 2, 240, 3000);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        var result = augmentor.buildDetailBrief("Spring Boot RestClient 官方文档", List.of(
                new SearchResultItem("Spring 官网", base + "/landing", "Spring 产品概览和首页", Instant.EPOCH, "web"),
                new SearchResultItem("Spring RestClient Reference", base + "/docs/restclient", "Official documentation and guide", Instant.EPOCH, "web")
        ));

        assertTrue(result.isPresent());
        assertEquals(base + "/docs/restclient", result.get().link());
        assertTrue(result.get().summary().contains("official guide") || result.get().summary().contains("reference documentation"), result.get().summary());
    }

    @Test
    void shouldPreferRecentBreakingPageWhenQueryAsksLatestNews() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analysis/o3", exchange -> {
            byte[] payload = """
                    <html>
                      <head><title>OpenAI o3 架构分析</title></head>
                      <body><article><p>这是一篇较早的技术分析文章，主要解释模型原理。</p></article></body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/news/o3-latest", exchange -> {
            byte[] payload = """
                    <html>
                      <head>
                        <title>OpenAI 发布 o3 最新更新</title>
                        <meta name="description" content="最新更新：OpenAI 公布 o3 新进展和上线节奏。">
                      </head>
                      <body>
                        <article>
                          <p>最新消息显示 OpenAI 已公布 o3 的新能力和发布时间安排。</p>
                          <p>本文集中说明当前版本更新和公开动态。</p>
                        </article>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        SearchResultDetailAugmentor augmentor = new SearchResultDetailAugmentor(true, 3000, 2, 240, 3000);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        var result = augmentor.buildDetailBrief("OpenAI 最新 o3 新闻", List.of(
                new SearchResultItem("OpenAI o3 深度分析", base + "/analysis/o3", "模型原理解读", Instant.now().minus(40, ChronoUnit.DAYS), "web"),
                new SearchResultItem("OpenAI 发布 o3 最新更新", base + "/news/o3-latest", "最新进展和发布时间", Instant.now().minus(12, ChronoUnit.HOURS), "web")
        ));

        assertTrue(result.isPresent());
        assertEquals(base + "/news/o3-latest", result.get().link());
        assertTrue(result.get().summary().contains("最新消息") || result.get().summary().contains("最新更新"), result.get().summary());
    }

    @Test
    void shouldParseMarkdownSearchOutputAndReadDetailPage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/detail", exchange -> {
            byte[] payload = """
                    <html>
                      <head>
                        <title>MindOS 详情页</title>
                        <meta name="description" content="这页解释了为什么要先点开最贴题的结果。">
                      </head>
                      <body>
                        <article>
                          <p>正文说明应先从候选里找到最接近问题的一条，再根据详情页内容输出结论。</p>
                        </article>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        SearchResultDetailAugmentor augmentor = new SearchResultDetailAugmentor(true, 3000, 3, 240, 3000);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/detail";

        String output = augmentor.augmentRenderedSearchOutput("MindOS 详情", """
                这是搜索结果：
                1. [MindOS 详情页](%s) - 解释如何先点开最贴题的链接
                """.formatted(url));

        assertTrue(output.contains("最相关详情"), output);
        assertTrue(output.contains(url), output);
        assertTrue(output.contains("先点开最贴题的结果") || output.contains("详情页内容输出结论"), output);
    }

    @Test
    void shouldExposeStructuredDetailAugmentation() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/detail", exchange -> {
            byte[] payload = """
                    <html>
                      <head>
                        <title>MindOS 架构详情</title>
                        <meta name="description" content="这页解释了 Hermes 单链路下的 detail enrich。">
                      </head>
                      <body>
                        <article>
                          <p>正文说明 detail enrich 不只是改写文本，还要留下结构化 title、link、summary。</p>
                        </article>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        SearchResultDetailAugmentor augmentor = new SearchResultDetailAugmentor(true, 3000, 3, 240, 3000);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/detail";

        SearchResultDetailAugmentor.DetailAugmentation augmentation = augmentor.augment("MindOS 架构", """
                1. [MindOS 架构详情](%s) - Hermes 单链路 detail enrich 说明
                """.formatted(url));

        assertTrue(augmentation.detailApplied());
        assertTrue(augmentation.output().contains("最相关详情"), augmentation.output());
        assertEquals("MindOS 架构详情", augmentation.detail().title());
        assertEquals(url, augmentation.detail().link());
        assertTrue(augmentation.detail().summary().contains("结构化") || augmentation.detail().summary().contains("detail enrich"), augmentation.detail().summary());
    }

    @Test
    void shouldKeepMultiResultSummaryQueryAsListInsteadOfCollapsingToSingleDetail() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/detail", exchange -> {
            byte[] payload = """
                    <html>
                      <head><title>单条详情页</title></head>
                      <body><article><p>这里是一篇会被单页读取命中的详情内容。</p></article></body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        SearchResultDetailAugmentor augmentor = new SearchResultDetailAugmentor(true, 3000, 3, 240, 3000);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/detail";
        String rawOutput = """
                1. 伊朗局势最新消息
                %s
                2. 美国与伊朗谈判进展
                %s
                """.formatted(url, url);

        String output = augmentor.augmentRenderedSearchOutput("伊朗最新消息给我总结前5条", rawOutput);

        assertEquals(rawOutput, output);
        assertTrue(!output.contains("最相关详情"), output);
    }
}

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
}

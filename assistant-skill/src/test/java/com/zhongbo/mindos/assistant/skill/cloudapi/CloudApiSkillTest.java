package com.zhongbo.mindos.assistant.skill.cloudapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudApiSkillTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ─── routing metadata / registry detection ───────────────────────────────

    @Test
    void detectsBySkillNamePrefix() {
        CloudApiSkill skill = buildSkill("translate.text", List.of("翻译"), null, null, null, null);
        SkillRegistry registry = routingRegistry(skill);
        assertEquals("translate.text", detectSkillName(registry, "translate.text hello"));
        assertEquals("translate.text", detectSkillName(registry, "translate.text"));
        assertTrue(catalog(registry).detectSkillName("echo hello").isEmpty());
    }

    @Test
    void detectsByKeyword() {
        CloudApiSkill skill = buildSkill("translate.text", List.of("翻译", "translate", "语言"), null, null, null, null);
        SkillRegistry registry = routingRegistry(skill);
        assertEquals("translate.text", detectSkillName(registry, "帮我翻译这段话"));
        assertEquals("translate.text", detectSkillName(registry, "translate this sentence"));
        assertEquals("translate.text", detectSkillName(registry, "语言切换"));
        assertTrue(catalog(registry).detectSkillName("查一下天气").isEmpty());
    }

    @Test
    void returnsNoCandidateForBlankInput() {
        CloudApiSkill skill = buildSkill("my.skill", List.of("keyword"), null, null, null, null);
        SkillRegistry registry = routingRegistry(skill);
        assertTrue(catalog(registry).detectSkillName(null).isEmpty());
        assertTrue(catalog(registry).detectSkillName("   ").isEmpty());
    }

    @Test
    void detectionIsCaseInsensitive() {
        CloudApiSkill skill = buildSkill("weather.query", List.of("Weather"), null, null, null, null);
        assertEquals("weather.query", detectSkillName(routingRegistry(skill), "check WEATHER today"));
    }

    // ─── resolveTemplate() ───────────────────────────────────────────────────

    @Test
    void resolvesInputPlaceholder() {
        CloudApiSkill skill = buildSkill("s", List.of(), null, null, null, null);
        SkillContext ctx = new SkillContext("u1", "", Map.of("input", "hello world"));
        assertEquals("echo: hello world", skill.resolveTemplate("echo: ${input}", ctx));
    }

    @Test
    void resolvesInputFieldPlaceholder() {
        CloudApiSkill skill = buildSkill("s", List.of(), null, null, null, null);
        SkillContext ctx = new SkillContext("u1", "query", Map.of("city", "Beijing"));
        assertEquals("Beijing is nice", skill.resolveTemplate("${input.city} is nice", ctx));
    }

    @Test
    void resolvesApiKeyPlaceholder() {
        CloudApiSkill skill = buildSkill("s", List.of(), null, null, "secret-key", null);
        SkillContext ctx = SkillContext.of("u1", "");
        assertEquals("key=secret-key", skill.resolveTemplate("key=${apiKey}", ctx));
    }

    // ─── run() with real HTTP server ─────────────────────────────────────────

    @Test
    void runsGetRequestWithQueryParamsAndExtractsResult() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/weather", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getQuery());
            String body = "{\"current\":{\"temperature\":22,\"weather_descriptions\":\"Sunny\",\"humidity\":40}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/weather";
        CloudApiSkillDefinition def = new CloudApiSkillDefinition(
                "weather.query",
                "Query weather",
                List.of("天气", "weather"),
                url,
                "GET",
                Map.of(),
                Map.of("city", "${input.city}"),
                null,
                null,
                "X-API-Key",
                "current",
                "天气：${weather_descriptions}, 温度：${temperature}°C, 湿度：${humidity}%"
        );
        CloudApiSkill skill = new CloudApiSkill(def,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper);

        SkillResult result = skill.run(new SkillContext("u1", "查一下北京天气", Map.of("city", "Beijing")));

        assertTrue(result.success());
        assertTrue(result.output().contains("Sunny"));
        assertTrue(result.output().contains("22°C"));
        assertTrue(result.output().contains("40%"));
        assertNotNull(receivedQuery.get());
        assertTrue(receivedQuery.get().contains("city=Beijing"));
    }

    @Test
    void runsPostRequestWithBodyTemplate() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedApiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/translate", exchange -> {
            receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = "{\"result\":\"你好，世界\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/translate";
        CloudApiSkillDefinition def = new CloudApiSkillDefinition(
                "translate.text",
                "Translates text",
                List.of("翻译", "translate"),
                url,
                "POST",
                Map.of(),
                Map.of(),
                "{\"text\":\"${input}\",\"target\":\"zh\"}",
                "my-api-key",
                "X-API-Key",
                "result",
                null
        );
        CloudApiSkill skill = new CloudApiSkill(def,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper);

        SkillResult result = skill.run(new SkillContext("u1", "", Map.of("input", "Hello, world")));

        assertTrue(result.success());
        assertEquals("你好，世界", result.output());
        assertNotNull(receivedBody.get());
        assertTrue(receivedBody.get().contains("Hello, world"));
        assertEquals("my-api-key", receivedApiKey.get());
    }

    @Test
    void returnsFailureOnHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        CloudApiSkillDefinition def = new CloudApiSkillDefinition(
                "my.api",
                "Some API",
                List.of("api"),
                url,
                "GET",
                Map.of(),
                Map.of(),
                null,
                null,
                "X-API-Key",
                null,
                null
        );
        CloudApiSkill skill = new CloudApiSkill(def,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper);

        SkillResult result = skill.run(new SkillContext("u1", "", Map.of("input", "test")));

        assertFalse(result.success());
        assertTrue(result.output().contains("500"));
    }

    @Test
    void returnsRawJsonWhenNoResultPath() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            String body = "{\"value\":42}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/data";
        CloudApiSkillDefinition def = new CloudApiSkillDefinition(
                "raw.api",
                "Raw API",
                List.of("raw"),
                url,
                "GET",
                Map.of(),
                Map.of(),
                null,
                null,
                "X-API-Key",
                null,
                null
        );
        CloudApiSkill skill = new CloudApiSkill(def,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper);

        SkillResult result = skill.run(new SkillContext("u1", "", Map.of("input", "get raw")));

        assertTrue(result.success());
        assertTrue(result.output().contains("42"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private CloudApiSkill buildSkill(String name, List<String> keywords,
                                     String url, String method,
                                     String apiKey, String resultPath) {
        CloudApiSkillDefinition def = new CloudApiSkillDefinition(
                name,
                "Test skill",
                keywords,
                url != null ? url : "https://example.com/api",
                method != null ? method : "GET",
                Map.of(),
                Map.of(),
                null,
                apiKey,
                "X-API-Key",
                resultPath,
                null
        );
        return new CloudApiSkill(def,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper);
    }

    private SkillRegistry routingRegistry(Skill skill) {
        return new SkillRegistry(List.of(skill));
    }

    private String detectSkillName(SkillRegistry registry, String input) {
        return catalog(registry).detectSkillName(input).orElse("");
    }

    private DefaultSkillCatalog catalog(SkillRegistry registry) {
        return new DefaultSkillCatalog(registry, null, new com.zhongbo.mindos.assistant.skill.SkillRoutingProperties());
    }
}

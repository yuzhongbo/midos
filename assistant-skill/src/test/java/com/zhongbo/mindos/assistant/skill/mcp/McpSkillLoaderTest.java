package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSkillLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldRegisterAndExecuteMcpToolSkill() throws Exception {
        AtomicBoolean authHeaderSeen = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer loader-token".equals(auth)) {
                authHeaderSeen.set(true);
            }
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            Map<String, Object> params = request.get("params") instanceof Map<?, ?> rawParams
                    ? rawParams.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    e -> String.valueOf(e.getKey()), Map.Entry::getValue))
                    : Map.of();
            byte[] response = switch (method) {
                case "initialize" -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
                case "tools/list" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("tools", List.of(
                                Map.of("name", "searchDocs", "description", "Search docs")
                        ))
                ));
                case "tools/call" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("content", List.of(Map.of(
                                "type", "text",
                                "text", "docs-result:" + params.get("arguments")
                        )))
                ));
                default -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        SkillRegistry registry = new SkillRegistry(List.<Skill>of());
        McpSkillLoader loader = new McpSkillLoader(registry, new McpJsonRpcClient(), "docs:" + url, "");

        int loaded = loader.loadServer("docs", url, Map.of("Authorization", "Bearer loader-token"));
        Skill loadedSkill = registry.getSkill("mcp.docs.searchDocs").orElseThrow();
        SkillResult result = registry.getSkill("mcp.docs.searchDocs")
                .orElseThrow()
                .run(new SkillContext("u1", "find auth guide", Map.of("query", "auth")));

        assertEquals(1, loaded);
        assertTrue(registry.getSkill("mcp.docs.searchDocs").isPresent());
        assertTrue(loadedSkill.supports("search docs for auth guide"));
        assertTrue(result.success());
        assertTrue(result.output().contains("query=auth"));
        assertTrue(authHeaderSeen.get());
    }

    @Test
    void shouldApplyConfiguredHeadersWhenLoadingConfiguredServers() throws Exception {
        AtomicBoolean authHeaderSeen = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("Bearer cfg-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                authHeaderSeen.set(true);
            }
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            byte[] response = switch (method) {
                case "initialize" -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
                case "tools/list" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("tools", List.of(
                                Map.of("name", "searchDocs", "description", "Search docs")
                        ))
                ));
                default -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        SkillRegistry registry = new SkillRegistry(List.<Skill>of());
        McpSkillLoader loader = new McpSkillLoader(
                registry,
                new McpJsonRpcClient(),
                "docs:" + url,
                "docs:Authorization=Bearer cfg-token"
        );

        int loaded = loader.loadConfiguredServers();

        assertEquals(1, loaded);
        assertTrue(registry.getSkill("mcp.docs.searchDocs").isPresent());
        assertTrue(authHeaderSeen.get());
    }

    @Test
    void shouldLoadBraveMcpFromDedicatedProperties() throws Exception {
        AtomicBoolean braveTokenSeen = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("brave-test-token".equals(exchange.getRequestHeaders().getFirst("X-Subscription-Token"))) {
                braveTokenSeen.set(true);
            }
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            byte[] response = switch (method) {
                case "initialize" -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
                case "tools/list" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("tools", List.of(
                                Map.of("name", "webSearch", "description", "Search web")
                        ))
                ));
                default -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        SkillRegistry registry = new SkillRegistry(List.<Skill>of());
        McpSkillLoader loader = new McpSkillLoader(
                registry,
                new McpJsonRpcClient(),
                "",
                "",
                true,
                "brave",
                url,
                "brave-test-token",
                "X-Subscription-Token"
        );

        int loaded = loader.loadConfiguredServers();

        assertEquals(1, loaded);
        assertTrue(registry.getSkill("mcp.brave.webSearch").isPresent());
        assertTrue(braveTokenSeen.get());
    }

    @Test
    void shouldRegisterAndExecuteBraveRestSearchFromConfiguredServers() throws Exception {
        AtomicBoolean braveTokenSeen = new AtomicBoolean(false);
        AtomicBoolean querySeen = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            if ("brave-rest-token".equals(exchange.getRequestHeaders().getFirst("X-Subscription-Token"))) {
                braveTokenSeen.set(true);
            }
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null && rawQuery.contains("q=")) {
                String q = URLDecoder.decode(rawQuery.substring(rawQuery.indexOf("q=") + 2), StandardCharsets.UTF_8);
                if ("artificial intelligence".equals(q)) {
                    querySeen.set(true);
                }
            }
            byte[] response = json(Map.of(
                    "web", Map.of("results", List.of(
                            Map.of(
                                    "title", "AI news headline",
                                    "description", "Latest artificial intelligence update",
                                    "url", "https://example.com/ai"
                            )
                    ))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search";
        SkillRegistry registry = new SkillRegistry(List.<Skill>of());
        McpSkillLoader loader = new McpSkillLoader(
                registry,
                new McpJsonRpcClient(),
                "bravesearch:" + url,
                "bravesearch:X-Subscription-Token=brave-rest-token"
        );

        int loaded = loader.loadConfiguredServers();
        SkillResult result = registry.getSkill("mcp.bravesearch.webSearch")
                .orElseThrow()
                .run(new SkillContext("u1", "查看新闻 artificial intelligence", Map.of("query", "artificial intelligence")));

        assertEquals(1, loaded);
        assertTrue(registry.getSkill("mcp.bravesearch.webSearch").isPresent());
        assertTrue(braveTokenSeen.get());
        assertTrue(querySeen.get());
        assertTrue(result.success());
        assertTrue(result.output().contains("AI news headline"));
    }

    @Test
    void shouldIgnorePlaceholderServerUrlEntries() {
        SkillRegistry registry = new SkillRegistry(List.<Skill>of());
        McpSkillLoader loader = new McpSkillLoader(
                registry,
                new McpJsonRpcClient(),
                "qwensearch:https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp,bravesearch:REPLACE_WITH_BRAVE_MCP_URL",
                ""
        );

        Map<String, String> parsed = loader.parseServerConfig(loader.getConfiguredServers());

        assertEquals(1, parsed.size());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp", parsed.get("qwensearch"));
        assertTrue(!parsed.containsKey("bravesearch"));
    }

    @Test
    void shouldIgnorePlaceholderHeaderValues() {
        SkillRegistry registry = new SkillRegistry(List.<Skill>of());
        McpSkillLoader loader = new McpSkillLoader(
                registry,
                new McpJsonRpcClient(),
                "",
                "qwensearch:Authorization=Bearer REPLACE_WITH_QWEN_KEY,bravesearch:X-Subscription-Token=REPLACE_WITH_BRAVE_KEY"
        );

        Map<String, Map<String, String>> parsed = loader.parseHeadersConfig(loader.getConfiguredServerHeaders());

        assertTrue(parsed.isEmpty());
    }

    private byte[] json(Map<String, Object> value) throws IOException {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }
}

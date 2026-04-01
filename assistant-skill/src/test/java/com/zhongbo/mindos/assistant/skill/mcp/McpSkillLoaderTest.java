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

    private byte[] json(Map<String, Object> value) throws IOException {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }
}

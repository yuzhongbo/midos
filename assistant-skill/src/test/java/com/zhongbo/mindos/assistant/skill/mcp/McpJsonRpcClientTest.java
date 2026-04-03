package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonRpcClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldInitializeListToolsAndCallTool() throws Exception {
        AtomicBoolean initializeHeaderSeen = new AtomicBoolean(false);
        AtomicBoolean listHeaderSeen = new AtomicBoolean(false);
        AtomicBoolean callHeaderSeen = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if ("initialize".equals(method)) {
                initializeHeaderSeen.set("Bearer test-token".equals(auth));
            }
            if ("tools/list".equals(method)) {
                listHeaderSeen.set("Bearer test-token".equals(auth));
            }
            if ("tools/call".equals(method)) {
                callHeaderSeen.set("Bearer test-token".equals(auth));
            }
            byte[] response = switch (method) {
                case "initialize" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("serverInfo", Map.of("name", "fake-mcp", "version", "1.0"))
                ));
                case "tools/list" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("tools", List.of(
                                Map.of("name", "weather", "description", "Lookup weather")
                        ))
                ));
                case "tools/call" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("content", List.of(Map.of("type", "text", "text", "sunny")))
                ));
                default -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "error", Map.of("message", "Unsupported method: " + method)
                ));
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        McpJsonRpcClient client = new McpJsonRpcClient();
        Map<String, String> headers = Map.of("Authorization", "Bearer test-token");

        client.initialize(baseUrl, headers);
        List<McpToolDefinition> tools = client.listTools("docs", baseUrl, headers);
        String output = client.callTool(baseUrl, "weather", Map.of("city", "Shanghai"), headers);

        assertEquals(1, tools.size());
        assertEquals("mcp.docs.weather", tools.get(0).skillName());
        assertEquals("Lookup weather", tools.get(0).description());
        assertEquals("Bearer test-token", tools.get(0).headers().get("Authorization"));
        assertEquals("sunny", output);
        assertTrue(initializeHeaderSeen.get());
        assertTrue(listHeaderSeen.get());
        assertTrue(callHeaderSeen.get());
    }

    @Test
    void shouldRetryTransientHttpFailureWhenCallingTool() throws Exception {
        AtomicInteger callAttempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            byte[] response;
            int statusCode = 200;
            if ("tools/call".equals(method) && callAttempts.incrementAndGet() == 1) {
                response = json(Map.of("error", "temporary_unavailable"));
                statusCode = 503;
            } else if ("tools/call".equals(method)) {
                response = json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("content", List.of(Map.of("type", "text", "text", "retry-ok")))
                ));
            } else {
                response = json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("serverInfo", Map.of("name", "fake-mcp", "version", "1.0"))
                ));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        McpJsonRpcClient client = new McpJsonRpcClient();

        String output = client.callTool(baseUrl, "weather", Map.of("city", "Shanghai"), Map.of());

        assertEquals("retry-ok", output);
        assertEquals(2, callAttempts.get());
    }

    private byte[] json(Map<String, Object> value) throws IOException {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }
}


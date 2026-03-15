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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
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

        client.initialize(baseUrl);
        List<McpToolDefinition> tools = client.listTools("docs", baseUrl);
        String output = client.callTool(baseUrl, "weather", Map.of("city", "Shanghai"));

        assertEquals(1, tools.size());
        assertEquals("mcp.docs.weather", tools.get(0).skillName());
        assertEquals("Lookup weather", tools.get(0).description());
        assertEquals("sunny", output);
    }

    private byte[] json(Map<String, Object> value) throws IOException {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }
}


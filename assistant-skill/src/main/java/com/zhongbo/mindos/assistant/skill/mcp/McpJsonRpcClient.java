package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal HTTP JSON-RPC client for MCP tool discovery and invocation.
 *
 * Supported methods:
 * - initialize
 * - tools/list
 * - tools/call
 */
public class McpJsonRpcClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public McpJsonRpcClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), new ObjectMapper().findAndRegisterModules());
    }

    McpJsonRpcClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public void initialize(String serverUrl) {
        initialize(serverUrl, Map.of());
    }

    public void initialize(String serverUrl, Map<String, String> headers) {
        invoke(serverUrl, "initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "MindOS", "version", "0.1.0")
        ), headers);
    }

    public List<McpToolDefinition> listTools(String serverAlias, String serverUrl) {
        return listTools(serverAlias, serverUrl, Map.of());
    }

    public List<McpToolDefinition> listTools(String serverAlias, String serverUrl, Map<String, String> headers) {
        Map<String, Object> result = invoke(serverUrl, "tools/list", Map.of(), headers);
        Object rawTools = result.get("tools");
        if (!(rawTools instanceof List<?> tools)) {
            return List.of();
        }

        List<McpToolDefinition> mapped = new ArrayList<>();
        for (Object tool : tools) {
            if (!(tool instanceof Map<?, ?> rawTool)) {
                continue;
            }
            Object rawName = rawTool.containsKey("name") ? rawTool.get("name") : "";
            String name = String.valueOf(rawName).trim();
            if (name.isBlank()) {
                continue;
            }
            Object rawDescription = rawTool.containsKey("description") ? rawTool.get("description") : "";
            String description = String.valueOf(rawDescription).trim();
            mapped.add(new McpToolDefinition(serverAlias, serverUrl, name, description, headers));
        }
        return List.copyOf(mapped);
    }

    public String callTool(String serverUrl, String toolName, Map<String, Object> arguments) {
        return callTool(serverUrl, toolName, arguments, Map.of());
    }

    public String callTool(String serverUrl,
                           String toolName,
                           Map<String, Object> arguments,
                           Map<String, String> headers) {
        Map<String, Object> result = invoke(serverUrl, "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ), headers);
        return renderResult(result);
    }

    private Map<String, Object> invoke(String serverUrl,
                                       String method,
                                       Map<String, Object> params,
                                       Map<String, String> headers) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", method,
                    "params", params == null ? Map.of() : params
            ));

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(serverUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() == null || header.getKey().isBlank()) {
                        continue;
                    }
                    if (header.getValue() == null || header.getValue().isBlank()) {
                        continue;
                    }
                    requestBuilder.header(header.getKey().trim(), header.getValue().trim());
                }
            }
            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCP server returned HTTP " + response.statusCode());
            }

            Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (payload.get("error") instanceof Map<?, ?> error) {
                Object message = error.get("message");
                throw new IllegalStateException("MCP error: " + String.valueOf(message));
            }
            Object result = payload.get("result");
            if (!(result instanceof Map<?, ?> rawResult)) {
                return Map.of();
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            rawResult.forEach((key, value) -> mapped.put(String.valueOf(key), value));
            return Map.copyOf(mapped);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse MCP response for method " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling MCP method " + method, e);
        }
    }

    private String renderResult(Map<String, Object> result) {
        Object content = result.get("content");
        if (content instanceof List<?> contentList && !contentList.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> contentMap) {
                    Object text = contentMap.get("text");
                    if (text != null) {
                        parts.add(String.valueOf(text));
                    }
                } else if (item != null) {
                    parts.add(String.valueOf(item));
                }
            }
            if (!parts.isEmpty()) {
                return String.join("\n", parts);
            }
        }

        Object structuredContent = result.get("structuredContent");
        if (structuredContent != null) {
            try {
                return objectMapper.writeValueAsString(structuredContent);
            } catch (IOException ignored) {
                return String.valueOf(structuredContent);
            }
        }

        try {
            return objectMapper.writeValueAsString(result);
        } catch (IOException ignored) {
            return String.valueOf(result);
        }
    }
}


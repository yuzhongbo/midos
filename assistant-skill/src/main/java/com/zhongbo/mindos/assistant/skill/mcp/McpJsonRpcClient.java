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
import java.util.logging.Logger;

/**
 * Minimal HTTP JSON-RPC client for MCP tool discovery and invocation.
 *
 * Supported methods:
 * - initialize
 * - tools/list
 * - tools/call
 */
public class McpJsonRpcClient {

    private static final Logger LOGGER = Logger.getLogger(McpJsonRpcClient.class.getName());
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 250L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

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
        int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", method,
                    "params", params == null ? Map.of() : params
            ));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize MCP request for method " + method + ": " + rootCauseMessage(e), e);
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(serverUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(REQUEST_TIMEOUT)
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

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        if (attempt < maxAttempts && isRetryableStatus(response.statusCode())) {
                            logRetry(method, serverUrl, attempt, "http_" + response.statusCode());
                            sleepBeforeRetry();
                            continue;
                        }
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
                    String cause = rootCauseMessage(e);
                    if (attempt < maxAttempts && isRetryableTransportError(cause)) {
                        logRetry(method, serverUrl, attempt, cause);
                        sleepBeforeRetry();
                        continue;
                    }
                    throw new IllegalStateException("Failed to parse MCP response for method " + method + ": " + cause, e);
                }
            }
            throw new IllegalStateException("Failed to call MCP method " + method);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling MCP method " + method, e);
        }
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 409
                || statusCode == 425
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private boolean isRetryableTransportError(String message) {
        String normalized = normalize(message);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("connection reset")
                || normalized.contains("broken pipe")
                || normalized.contains("connection refused")
                || normalized.contains("timed out")
                || normalized.contains("timeout")
                || normalized.contains("eof")
                || normalized.contains("stream closed")
                || normalized.contains("stream reset")
                || normalized.contains("goaway")
                || normalized.contains("http/2 error")
                || normalized.contains("reset by peer");
    }

    private void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MILLIS);
    }

    private void logRetry(String method, String serverUrl, int attempt, String reason) {
        LOGGER.info(() -> "{\"event\":\"mcp.client.retry\",\"method\":\""
                + escapeJson(method)
                + "\",\"serverUrl\":\""
                + escapeJson(serverUrl)
                + "\",\"attempt\":"
                + attempt
                + ",\"reason\":\""
                + escapeJson(reason)
                + "\"}");
    }

    private String rootCauseMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = normalize(current.getMessage());
        return message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String escapeJson(String value) {
        return normalize(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
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


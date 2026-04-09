package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

final class SerperSearchRestClient extends McpJsonRpcClient {

    private static final Logger LOGGER = Logger.getLogger(SerperSearchRestClient.class.getName());
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 250L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 425, 429, 500, 502, 503, 504);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    SerperSearchRestClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper().findAndRegisterModules());
    }

    SerperSearchRestClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String callTool(String serverUrl,
                           String toolName,
                           Map<String, Object> arguments,
                           Map<String, String> headers) {
        String query = stringValue(arguments == null ? null : arguments.get("query"));
        if (query.isBlank()) {
            throw new IllegalStateException("Param:query is required");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(writeBody(query), StandardCharsets.UTF_8));
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String key = stringValue(header.getKey());
                String value = stringValue(header.getValue());
                if (!key.isBlank() && !value.isBlank()) {
                    builder.header(key, value);
                }
            }
        }
        HttpRequest request = builder.build();
        try {
            for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (attempt < DEFAULT_MAX_ATTEMPTS && RETRYABLE_STATUS.contains(response.statusCode())) {
                        sleepBeforeRetry();
                        continue;
                    }
                    throw new IllegalStateException("Serper search returned HTTP " + response.statusCode());
                }
                return renderResponse(query, response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Serper search", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Serper search", e);
        }
        throw new IllegalStateException("Failed to call Serper search");
    }

    private String writeBody(String query) {
        try {
            return objectMapper.writeValueAsString(Map.of("q", query));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Serper request body", e);
        }
    }

    private String renderResponse(String query, String body) throws IOException {
        Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
        List<Map<String, Object>> results = extractResults(payload);
        if (results.isEmpty()) {
            return "未找到与“" + query + "”相关的 Serper 搜索结果。";
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> result : results) {
            String title = stringValue(firstNonNull(result, "title", "headline", "name"));
            String description = stringValue(firstNonNull(result, "snippet", "description"));
            String url = stringValue(firstNonNull(result, "link", "url"));
            if (title.isBlank() && description.isBlank() && url.isBlank()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(index).append(". ");
            line.append(!title.isBlank() ? title : (url.isBlank() ? "搜索结果" : url));
            if (!description.isBlank()) {
                line.append(" - ").append(description);
            }
            if (!url.isBlank()) {
                line.append("\n").append(url);
            }
            lines.add(line.toString());
            index++;
            if (lines.size() >= 5) {
                break;
            }
        }
        return lines.isEmpty()
                ? "未找到与“" + query + "”相关的 Serper 搜索结果。"
                : "Serper 搜索（" + query + "）结果：\n" + String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractResults(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        for (String key : List.of("organic", "news", "articles", "items", "results")) {
            Object value = payload.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        out.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        Object news = payload.get("news");
        if (news instanceof Map<?, ?> map) {
            Object value = map.get("value");
            if (value instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        out.add(new LinkedHashMap<>((Map<String, Object>) m));
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    private Object firstNonNull(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MILLIS);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}


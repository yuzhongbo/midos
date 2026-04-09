package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SerpApiSearchRestClient extends McpJsonRpcClient {

    private static final Logger LOGGER = Logger.getLogger(SerpApiSearchRestClient.class.getName());
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 250L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern TRAILING_RESULT_COUNT_PATTERN = Pattern.compile("(?:^|\\s)(?:前|要|看)?\\s*([0-9一二两三四五六七八九十]+)\\s*(?:条|个)(?:新闻|资讯|结果)?\\s*$");
    private static final Set<String> SUPPORTED_QUERY_PARAMETERS = Set.of(
            "engine",
            "google_domain",
            "hl",
            "gl",
            "location",
            "num",
            "start",
            "safe",
            "device",
            "tbm",
            "tbs",
            "api_key"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    SerpApiSearchRestClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper().findAndRegisterModules());
    }

    SerpApiSearchRestClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String callTool(String serverUrl,
                           String toolName,
                           Map<String, Object> arguments,
                           Map<String, String> headers) {
        Map<String, Object> normalizedArguments = normalizeSearchArguments(arguments);
        String query = stringValue(normalizedArguments.get("query"));
        if (query.isBlank()) {
            throw new IllegalStateException("Param:query is required");
        }
        URI requestUri;
        try {
            requestUri = buildSearchUri(serverUrl, normalizedArguments, query);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to build SerpApi search request: " + causeMessage(ex), ex);
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String key = stringValue(header.getKey());
                String value = stringValue(header.getValue());
                if (!key.isBlank() && !value.isBlank() && !"api_key".equalsIgnoreCase(key)) {
                    requestBuilder.header(key, value);
                }
            }
        }

        HttpRequest request = requestBuilder.build();
        try {
            for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
                HttpResponse<String> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    String cause = causeMessage(e);
                    if (attempt < DEFAULT_MAX_ATTEMPTS && isRetryableTransportError(cause)) {
                        sleepBeforeRetry();
                        continue;
                    }
                    throw new IllegalStateException("Failed to call SerpApi search: " + cause, e);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (attempt < DEFAULT_MAX_ATTEMPTS && isRetryableStatus(response.statusCode())) {
                        sleepBeforeRetry();
                        continue;
                    }
                    throw new IllegalStateException("SerpApi search returned HTTP " + response.statusCode());
                }
                try {
                    return renderResponse(query, response.body());
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to parse SerpApi search response: " + causeMessage(e), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling SerpApi search", e);
        }
        throw new IllegalStateException("Failed to call SerpApi search");
    }

    private Map<String, Object> normalizeSearchArguments(Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        String originalQuery = stringValue(normalized.get("query"));
        if (originalQuery.isBlank()) {
            return normalized;
        }
        Matcher matcher = TRAILING_RESULT_COUNT_PATTERN.matcher(originalQuery);
        if (!matcher.find()) {
            return normalized;
        }
        if (!normalized.containsKey("num")) {
            int inferredCount = clampResultCount(parseCountToken(matcher.group(1)));
            if (inferredCount > 0) {
                normalized.put("num", inferredCount);
            }
        }
        String strippedQuery = (originalQuery.substring(0, matcher.start()) + originalQuery.substring(matcher.end())).trim();
        strippedQuery = strippedQuery.replaceAll("\\s+", " ");
        if (!strippedQuery.isBlank()) {
            normalized.put("query", strippedQuery);
        }
        return normalized;
    }

    private int parseCountToken(String token) {
        String normalized = stringValue(token);
        if (normalized.isBlank()) {
            return 0;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return switch (normalized) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> 0;
        };
    }

    private int clampResultCount(int count) {
        if (count <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(20, count));
    }

    private URI buildSearchUri(String serverUrl, Map<String, Object> arguments, String query) {
        String normalizedServerUrl = normalizeServerUrl(serverUrl);
        List<String> queryParameters = new ArrayList<>();
        appendQueryParameter(queryParameters, "q", query);
        if (arguments != null && !arguments.isEmpty()) {
            for (String parameter : SUPPORTED_QUERY_PARAMETERS) {
                appendQueryParameter(queryParameters, parameter, arguments.get(parameter));
            }
        }
        String separator = normalizedServerUrl.contains("?") ? "&" : "?";
        return URI.create(normalizedServerUrl + separator + String.join("&", queryParameters));
    }

    private String normalizeServerUrl(String serverUrl) {
        String candidate = stringValue(serverUrl);
        if (candidate.isBlank()) {
            return candidate;
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return candidate;
            }
            String rawPath = uri.getRawPath();
            if (rawPath == null || rawPath.isBlank() || !rawPath.contains("//")) {
                return candidate;
            }
            URI normalized = new URI(
                    uri.getScheme(),
                    uri.getRawUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    rawPath.replaceAll("/{2,}", "/"),
                    uri.getRawQuery(),
                    uri.getRawFragment()
            );
            return normalized.toString();
        } catch (Exception ex) {
            return candidate;
        }
    }

    private void appendQueryParameter(List<String> target, String name, Object rawValue) {
        if (target == null || name == null || name.isBlank() || rawValue == null) {
            return;
        }
        if (rawValue instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    values.add(encode(text));
                }
            }
            if (!values.isEmpty()) {
                target.add(name + "=" + String.join(",", values));
            }
            return;
        }
        String text = stringValue(rawValue);
        if (!text.isBlank()) {
            target.add(name + "=" + encode(text));
        }
    }

    private String renderResponse(String query, String body) throws IOException {
        List<Map<String, Object>> results = extractResults(objectMapper.readValue(body, new TypeReference<>() {}));
        if (results.isEmpty()) {
            return "未找到与“" + query + "”相关的 SerpApi 搜索结果。";
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> result : results) {
            String title = stringValue(firstNonNull(result, "title", "headline", "name"));
            String description = stringValue(firstNonNull(result, "snippet", "description", "summary"));
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
                ? "未找到与“" + query + "”相关的 SerpApi 搜索结果。"
                : "SerpApi 搜索（" + query + "）结果：\n" + String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractResults(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        for (String key : List.of("organic_results", "news_results", "top_stories", "inline_news_results", "organic", "news", "articles", "items", "results")) {
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
                    if (item instanceof Map<?, ?> nested) {
                        out.add(new LinkedHashMap<>((Map<String, Object>) nested));
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
        String normalized = normalizeText(message);
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String causeMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}


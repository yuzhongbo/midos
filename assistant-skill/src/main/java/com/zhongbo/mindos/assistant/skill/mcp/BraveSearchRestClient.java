package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URISyntaxException;
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

final class BraveSearchRestClient extends McpJsonRpcClient {

    private static final Logger LOGGER = Logger.getLogger(BraveSearchRestClient.class.getName());
    private static final int FIRST_TITLE_LOG_MAX_CHARS = 80;
    private static final int ERROR_PREVIEW_LOG_MAX_CHARS = 160;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 250L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern TRAILING_RESULT_COUNT_PATTERN = Pattern.compile("(?:^|\\s)(?:前|要|看)?\\s*([0-9一二两三四五六七八九十]+)\\s*(?:条|个)(?:新闻|资讯|结果)?\\s*$");

    private static final Set<String> SUPPORTED_QUERY_PARAMETERS = Set.of(
            "country",
            "search_lang",
            "ui_lang",
            "count",
            "offset",
            "safesearch",
            "freshness",
            "text_decorations",
            "spellcheck",
            "result_filter",
            "goggles",
            "units",
            "extra_snippets",
            "summary"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    BraveSearchRestClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), new ObjectMapper().findAndRegisterModules());
    }

    BraveSearchRestClient(HttpClient httpClient, ObjectMapper objectMapper) {
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
            logOutcome(toolName, null, 0, 0, "", "validation_error", "Param:query is required");
            throw new IllegalStateException("Param:query is required");
        }
        URI requestUri;
        try {
            requestUri = buildSearchUri(serverUrl, normalizedArguments, query);
        } catch (RuntimeException ex) {
            logOutcome(toolName, null, 0, 0, "", "request_build_error", rootCauseMessage(ex));
            throw new IllegalStateException("Failed to build Brave search request: " + rootCauseMessage(ex), ex);
        }
        logRequest(toolName, requestUri, normalizedArguments, headers);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String key = header.getKey() == null ? "" : header.getKey().trim();
                String value = header.getValue() == null ? "" : header.getValue().trim();
                if (!key.isBlank() && !value.isBlank()) {
                    requestBuilder.header(key, value);
                }
            }
        }

        HttpRequest request = requestBuilder.build();
        try {
            for (int attempt = 1; attempt <= DEFAULT_MAX_ATTEMPTS; attempt++) {
                HttpResponse<String> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    String cause = rootCauseMessage(e);
                    if (attempt < DEFAULT_MAX_ATTEMPTS && isRetryableTransportError(cause)) {
                        logRetry(toolName, requestUri, attempt, cause);
                        sleepBeforeRetry();
                        continue;
                    }
                    logOutcome(toolName, requestUri, 0, 0, "", "transport_error", cause);
                    throw new IllegalStateException("Failed to call Brave search: " + cause, e);
                }

                String errorPreview = summarizeErrorBody(response.body());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (attempt < DEFAULT_MAX_ATTEMPTS && isRetryableStatus(response.statusCode())) {
                        logRetry(toolName, requestUri, attempt, "http_" + response.statusCode());
                        sleepBeforeRetry();
                        continue;
                    }
                    String errorMessage = buildHttpErrorMessage(response.statusCode(), errorPreview);
                    logOutcome(toolName, requestUri, response.statusCode(), 0, "", "http_error", errorPreview);
                    throw new IllegalStateException(errorMessage);
                }

                try {
                    RenderedBraveResponse rendered = renderResponse(query, response.body());
                    logOutcome(toolName, requestUri, response.statusCode(), rendered.resultCount(), rendered.firstTitle(), "success", "");
                    return rendered.output();
                } catch (IOException e) {
                    logOutcome(toolName, requestUri, response.statusCode(), 0, "", "parse_error",
                            errorPreview.isBlank() ? rootCauseMessage(e) : errorPreview);
                    throw new IllegalStateException("Failed to parse Brave search response" + previewSuffix(errorPreview), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logOutcome(toolName, requestUri, 0, 0, "", "interrupted", rootCauseMessage(e));
            throw new IllegalStateException("Interrupted while calling Brave search", e);
        }
        throw new IllegalStateException("Failed to call Brave search");
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
        if (!normalized.containsKey("count")) {
            int inferredCount = clampResultCount(parseCountToken(matcher.group(1)));
            if (inferredCount > 0) {
                normalized.put("count", inferredCount);
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
            default -> {
                if (normalized.startsWith("十") && normalized.length() == 2) {
                    yield 10 + parseCountToken(normalized.substring(1));
                }
                if (normalized.endsWith("十") && normalized.length() == 2) {
                    yield parseCountToken(normalized.substring(0, 1)) * 10;
                }
                if (normalized.length() == 3 && normalized.charAt(1) == '十') {
                    yield parseCountToken(normalized.substring(0, 1)) * 10 + parseCountToken(normalized.substring(2));
                }
                yield 0;
            }
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
        queryParameters.add("q=" + encode(query));
        if (arguments != null && !arguments.isEmpty()) {
            for (String parameter : SUPPORTED_QUERY_PARAMETERS) {
                appendQueryParameters(queryParameters, parameter, arguments.get(parameter));
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
        } catch (IllegalArgumentException | URISyntaxException ex) {
            return candidate;
        }
    }

    private void appendQueryParameters(List<String> target, String name, Object rawValue) {
        if (target == null || name == null || name.isBlank() || rawValue == null) {
            return;
        }
        if (rawValue instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendQueryParameters(target, name, item);
            }
            return;
        }
        if (rawValue.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(rawValue);
            for (int i = 0; i < length; i++) {
                appendQueryParameters(target, name, java.lang.reflect.Array.get(rawValue, i));
            }
            return;
        }
        String value = stringValue(rawValue);
        if (!value.isBlank()) {
            target.add(encode(name) + "=" + encode(value));
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private void logRequest(String toolName, URI requestUri, Map<String, Object> arguments, Map<String, String> headers) {
        Map<String, Object> safeParams = new LinkedHashMap<>();
        safeParams.put("query", stringValue(arguments == null ? null : arguments.get("query")));
        if (arguments != null && !arguments.isEmpty()) {
            for (String parameter : SUPPORTED_QUERY_PARAMETERS) {
                Object value = arguments.get(parameter);
                if (value != null) {
                    safeParams.put(parameter, value);
                }
            }
        }
        List<String> headerNames = new ArrayList<>();
        if (headers != null && !headers.isEmpty()) {
            for (String headerName : headers.keySet()) {
                String normalized = stringValue(headerName);
                if (!normalized.isBlank()) {
                    headerNames.add(normalized);
                }
            }
        }
        LOGGER.info(() -> "{\"event\":\"brave.search.request\",\"tool\":\""
                + stringValue(toolName)
                + "\",\"url\":\""
                + stringValue(requestUri)
                + "\",\"params\":"
                + safeParams
                + ",\"headerNames\":"
                + headerNames
                + "}");
    }

    private void logOutcome( String toolName,
                             URI requestUri,
                             int statusCode,
                             int resultCount,
                             String firstTitle,
                             String phase,
                             String errorPreview) {
        LOGGER.info(() -> "{\"event\":\"brave.search.response\",\"tool\":\""
                + stringValue(toolName)
                + "\",\"url\":\""
                + escapeJson(stringValue(requestUri))
                + "\",\"phase\":\""
                + escapeJson(stringValue(phase))
                + "\",\"status\":"
                + statusCode
                + ",\"resultCount\":"
                + resultCount
                + ",\"firstTitle\":\""
                + escapeJson(truncate(firstTitle, FIRST_TITLE_LOG_MAX_CHARS))
                + "\",\"errorPreview\":\""
                + escapeJson(truncate(errorPreview, ERROR_PREVIEW_LOG_MAX_CHARS))
                + "\"}");
    }

    private void logRetry(String toolName, URI requestUri, int attempt, String reason) {
        LOGGER.info(() -> "{\"event\":\"brave.search.retry\",\"tool\":\""
                + escapeJson(stringValue(toolName))
                + "\",\"url\":\""
                + escapeJson(stringValue(requestUri))
                + "\",\"attempt\":"
                + attempt
                + ",\"reason\":\""
                + escapeJson(stringValue(reason))
                + "\"}");
    }

    private RenderedBraveResponse renderResponse(String query, String body) throws IOException {
        Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
        Object web = payload.get("web");
        if (!(web instanceof Map<?, ?> webMap)) {
            return new RenderedBraveResponse(body, 0, "");
        }
        Object results = webMap.get("results");
        if (!(results instanceof List<?> resultList) || resultList.isEmpty()) {
            return new RenderedBraveResponse("未找到与“" + query + "”相关的 Brave 搜索结果。", 0, "");
        }
        List<String> lines = new ArrayList<>();
        int index = 1;
        int resultCount = 0;
        String firstTitle = "";
        for (Object item : resultList) {
            if (!(item instanceof Map<?, ?> result)) {
                continue;
            }
            String title = stringValue(result.get("title"));
            String description = stringValue(result.get("description"));
            String url = stringValue(result.get("url"));
            if (title.isBlank() && description.isBlank() && url.isBlank()) {
                continue;
            }
            resultCount++;
            if (firstTitle.isBlank()) {
                firstTitle = !title.isBlank() ? title : (url.isBlank() ? "搜索结果" : url);
            }
            StringBuilder line = new StringBuilder();
            line.append(index).append(". ");
            if (!title.isBlank()) {
                line.append(title);
            } else {
                line.append(url.isBlank() ? "搜索结果" : url);
            }
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
        if (lines.isEmpty()) {
            return new RenderedBraveResponse(body, 0, "");
        }
        return new RenderedBraveResponse(
                "Brave 搜索（" + query + "）结果：\n" + String.join("\n", lines),
                resultCount,
                firstTitle
        );
    }

    private String truncate(String value, int maxChars) {
        String normalized = stringValue(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    private String summarizeErrorBody(String body) {
        String normalized = stringValue(body).replaceAll("\\s+", " ").trim();
        return truncate(normalized, ERROR_PREVIEW_LOG_MAX_CHARS);
    }

    private String buildHttpErrorMessage(int statusCode, String errorPreview) {
        if (statusCode == 403 && looksLikeCloudflareChallenge(errorPreview)) {
            return "Brave search returned HTTP 403 (blocked by upstream challenge). "
                    + "Please verify Brave key/header and source IP allowlist, then retry.";
        }
        return "Brave search returned HTTP " + statusCode + previewSuffix(errorPreview);
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
        String normalized = stringValue(message).toLowerCase();
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

    private boolean looksLikeCloudflareChallenge(String preview) {
        String normalized = stringValue(preview).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("<!doctype html")
                || normalized.contains("<html")
                || normalized.contains("just a moment")
                || normalized.contains("cloudflare");
    }

    private String previewSuffix(String preview) {
        String normalized = stringValue(preview);
        return normalized.isBlank() ? "" : ": " + normalized;
    }

    private String rootCauseMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = stringValue(current.getMessage());
        return message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String escapeJson(String value) {
        return stringValue(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record RenderedBraveResponse(String output, int resultCount, String firstTitle) {
    }
}


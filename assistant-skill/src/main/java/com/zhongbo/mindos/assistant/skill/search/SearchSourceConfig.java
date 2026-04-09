package com.zhongbo.mindos.assistant.skill.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SearchSourceConfig(String alias,
                                 String url,
                                 String searchUrl,
                                 String newsUrl,
                                 String apiKey,
                                 String apiKeyHeader) {

    public static SearchSourceConfig shortcut(String alias, String url, String apiKey, String apiKeyHeader) {
        String normalizedAlias = normalizeAlias(alias);
        String resolvedUrl = url == null ? "" : url.trim();
        return new SearchSourceConfig(normalizedAlias, resolvedUrl, resolvedUrl, "", apiKey, apiKeyHeader);
    }

    public static SearchSourceConfig brave(String alias, String url, String apiKey, String apiKeyHeader) {
        return shortcut(alias, url, apiKey, apiKeyHeader);
    }

    public static SearchSourceConfig serper(String searchUrl, String newsUrl, String apiKey) {
        return serper("serper", searchUrl, newsUrl, apiKey);
    }

    public static SearchSourceConfig serper(String alias, String searchUrl, String newsUrl, String apiKey) {
        String resolvedSearchUrl = searchUrl == null ? "" : searchUrl.trim();
        String resolvedNewsUrl = newsUrl == null ? "" : newsUrl.trim();
        String resolvedUrl = firstNonBlank(resolvedSearchUrl, resolvedNewsUrl);
        return new SearchSourceConfig(normalizeAlias(alias), resolvedUrl == null ? "" : resolvedUrl, resolvedSearchUrl, resolvedNewsUrl, apiKey, "X-API-KEY");
    }

    public static List<SearchSourceConfig> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<SearchSourceConfig> parsed = new ArrayList<>();
        for (String entry : raw.split(",")) {
            SearchSourceConfig config = parseEntry(entry);
            if (config != null) {
                parsed.add(config);
            }
        }
        return parsed.isEmpty() ? List.of() : List.copyOf(parsed);
    }

    public static SearchSourceConfig parseEntry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator >= trimmed.length() - 1) {
            return null;
        }

        String alias = normalizeAlias(trimmed.substring(0, separator));
        String value = trimmed.substring(separator + 1).trim();
        if (value.isBlank()) {
            return null;
        }

        String baseUrl = value;
        Map<String, String> params = new LinkedHashMap<>();
        int paramSeparator = value.indexOf(';');
        if (paramSeparator >= 0) {
            baseUrl = value.substring(0, paramSeparator).trim();
            String paramText = value.substring(paramSeparator + 1).trim();
            if (!paramText.isBlank()) {
                for (String part : paramText.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0 || eq >= part.length() - 1) {
                        continue;
                    }
                    String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String paramValue = part.substring(eq + 1).trim();
                    if (!key.isBlank() && !paramValue.isBlank()) {
                        params.put(key, paramValue);
                    }
                }
            }
        }

        String searchUrl = firstNonBlank(params.get("search-url"), params.get("search"));
        String newsUrl = firstNonBlank(params.get("news-url"), params.get("news"));
        String apiKey = firstNonBlank(params.get("api-key"), params.get("key"));
        String apiKeyHeader = firstNonBlank(params.get("api-key-header"), params.get("header"));
        return new SearchSourceConfig(alias, baseUrl, searchUrl, newsUrl, apiKey, apiKeyHeader);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isSerperLike() {
        String normalized = normalizeAlias(alias);
        return normalized.contains("serper")
                || containsHint(resolvedSearchUrl(), "serper.dev")
                || containsHint(resolvedNewsUrl(), "serper.dev");
    }

    public boolean hasSerperEndpoint() {
        return containsHint(resolvedSearchUrl(), "serper.dev")
                || containsHint(resolvedNewsUrl(), "serper.dev")
                || pathEndsWith(resolvedSearchUrl(), "/search")
                || pathEndsWith(resolvedNewsUrl(), "/news");
    }

    public boolean isBraveLike() {
        String normalized = normalizeAlias(alias);
        return normalized.contains("brave")
                || containsHint(resolvedSearchUrl(), "search.brave.com")
                || containsHint(resolvedSearchUrl(), "/res/v1/web/search")
                || containsHint(resolvedNewsUrl(), "search.brave.com")
                || containsHint(resolvedNewsUrl(), "/res/v1/web/search");
    }

    public boolean hasBraveEndpoint() {
        return containsHint(resolvedSearchUrl(), "search.brave.com")
                || pathEndsWith(resolvedSearchUrl(), "/res/v1/web/search")
                || containsHint(resolvedNewsUrl(), "search.brave.com")
                || pathEndsWith(resolvedNewsUrl(), "/res/v1/web/search");
    }

    public boolean isSerpApiLike() {
        String normalized = normalizeAlias(alias);
        return normalized.contains("serpapi")
                || containsHint(resolvedSearchUrl(), "serpapi.com")
                || containsHint(resolvedSearchUrl(), "search.json")
                || containsHint(resolvedNewsUrl(), "serpapi.com")
                || containsHint(resolvedNewsUrl(), "search.json");
    }

    public boolean hasSerpApiEndpoint() {
        return containsHint(resolvedSearchUrl(), "serpapi.com")
                || pathEndsWith(resolvedSearchUrl(), "/search.json")
                || pathEndsWith(resolvedSearchUrl(), "/search")
                || containsHint(resolvedNewsUrl(), "serpapi.com")
                || pathEndsWith(resolvedNewsUrl(), "/search.json")
                || pathEndsWith(resolvedNewsUrl(), "/search");
    }

    public String resolvedApiKeyHeader() {
        if (isSerpApiLike()) {
            return "api_key";
        }
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return apiKeyHeader.trim();
        }
        if (isBraveLike()) {
            return "X-Subscription-Token";
        }
        return "X-API-KEY";
    }

    public String resolvedSearchUrl() {
        if (searchUrl != null && !searchUrl.isBlank()) {
            return searchUrl.trim();
        }
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.trim();
    }

    public String resolvedNewsUrl() {
        if (newsUrl != null && !newsUrl.isBlank()) {
            return newsUrl.trim();
        }
        String base = resolvedSearchUrl();
        if (base.isBlank()) {
            return "";
        }
        String normalized = base.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/news") || normalized.endsWith("/search")) {
            return base;
        }
        if (normalized.contains("serper.dev")) {
            return stripTrailingPath(base) + "/news";
        }
        return base;
    }

    public String resolvedMcpUrl() {
        return resolvedSearchUrl();
    }

    private static String stripTrailingPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("/search")) {
            return trimmed.substring(0, trimmed.length() - "/search".length());
        }
        if (trimmed.endsWith("/news")) {
            return trimmed.substring(0, trimmed.length() - "/news".length());
        }
        return trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean containsHint(String value, String hint) {
        if (value == null || value.isBlank() || hint == null || hint.isBlank()) {
            return false;
        }
        return value.trim().toLowerCase(Locale.ROOT).contains(hint.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean pathEndsWith(String value, String suffix) {
        if (value == null || value.isBlank() || suffix == null || suffix.isBlank()) {
            return false;
        }
        String normalizedSuffix = suffix.trim().toLowerCase(Locale.ROOT);
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        try {
            String path = java.net.URI.create(candidate).getPath();
            if (path != null && !path.isBlank()) {
                return path.toLowerCase(Locale.ROOT).endsWith(normalizedSuffix);
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to raw-text suffix check
        }
        return candidate.endsWith(normalizedSuffix);
    }

    private static String normalizeAlias(String alias) {
        if (alias == null) {
            return "";
        }
        return alias.trim().toLowerCase(Locale.ROOT);
    }
}

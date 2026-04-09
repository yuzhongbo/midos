package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.search.SearchSourceConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class SerpApiSearchProvider implements SearchToolAdapter {

    @Override
    public boolean supports(SearchSourceConfig source) {
        return source != null && source.hasSerpApiEndpoint();
    }

    @Override
    public Optional<SearchToolBinding> adapt(SearchSourceConfig source, Map<String, String> headers) {
        if (source == null) {
            return Optional.empty();
        }
        String url = resolveSerpApiUrl(source.resolvedMcpUrl(), headers);
        if (url.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> mergedHeaders = copyHeaders(headers);
        McpToolDefinition tool = new McpToolDefinition(
                source.alias(),
                url,
                "webSearch",
                "SerpApi precise web/news search",
                mergedHeaders
        );
        return Optional.of(new SearchToolBinding(tool, new SerpApiSearchRestClient(), SearchRequestStyle.GET_QUERY, "SerpApi", source.resolvedNewsUrl(), "api_key"));
    }

    private String resolveSerpApiUrl(String serverUrl, Map<String, String> headers) {
        String resolved = serverUrl == null ? "" : serverUrl.trim();
        if (resolved.isBlank() || hasApiKeyQuery(resolved)) {
            return resolved;
        }
        String apiKey = resolveApiKey(headers);
        if (apiKey.isBlank()) {
            return resolved;
        }
        return appendApiKeyQuery(resolved, apiKey);
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(headers));
    }

    private String appendApiKeyQuery(String url, String value) {
        if (url == null || url.isBlank() || value == null || value.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }

    private boolean hasApiKeyQuery(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String query = URI.create(url.trim()).getRawQuery();
            if (query == null || query.isBlank()) {
                return false;
            }
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                String key = eq >= 0 ? part.substring(0, eq) : part;
                if ("api_key".equalsIgnoreCase(key.trim())) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ex) {
            return url.contains("api_key=");
        }
        return false;
    }

    private String resolveApiKey(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null) {
                String key = entry.getKey().trim();
                if (!"api_key".equalsIgnoreCase(key) && !"api-key".equalsIgnoreCase(key)) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }
}







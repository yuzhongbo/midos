package com.zhongbo.mindos.assistant.skill.search;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

final class SerpApiSearchProvider implements SearchProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    SerpApiSearchProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), new ObjectMapper().findAndRegisterModules());
    }

    SerpApiSearchProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SearchSourceConfig source) {
        return source != null && source.hasSerpApiEndpoint();
    }

    @Override
    public List<SearchResultItem> search(SearchSourceConfig source, SearchRequest request) throws Exception {
        String query = SearchProviderSupport.requireQuery(request);
        String endpoint = SearchProviderSupport.firstNonBlank(source == null ? "" : source.resolvedNewsUrl(), source == null ? "" : source.resolvedSearchUrl());
        if (query.isBlank() || endpoint.isBlank()) {
            return List.of();
        }
        String requestUrl = appendQueryParameter(endpoint, "q", query);
        String apiKey = SearchProviderSupport.resolveApiKey(source);
        if (!apiKey.isBlank() && !SearchProviderSupport.containsQueryParameter(requestUrl, "api_key")) {
            requestUrl = appendQueryParameter(requestUrl, "api_key", apiKey);
        }
        if (apiKey.isBlank() && !SearchProviderSupport.containsQueryParameter(requestUrl, "api_key")) {
            return List.of();
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofMillis(SearchProviderSupport.timeoutMillis(request)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("search_http_" + response.statusCode());
        }
        return SearchResponseParser.parse(objectMapper, response.body(), "SerpApi");
    }

    private String appendQueryParameter(String url, String name, String value) {
        if (url == null || url.isBlank() || name == null || name.isBlank() || value == null || value.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }
}

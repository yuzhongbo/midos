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

final class BraveSearchProvider implements SearchProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    BraveSearchProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), new ObjectMapper().findAndRegisterModules());
    }

    BraveSearchProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SearchSourceConfig source) {
        return source != null && source.hasBraveEndpoint();
    }

    @Override
    public List<SearchResultItem> search(SearchSourceConfig source, SearchRequest request) throws Exception {
        String query = SearchProviderSupport.requireQuery(request);
        String endpoint = SearchProviderSupport.firstNonBlank(source == null ? "" : source.resolvedNewsUrl(), source == null ? "" : source.resolvedSearchUrl());
        String apiKey = SearchProviderSupport.resolveApiKey(source);
        if (query.isBlank() || endpoint.isBlank() || apiKey.isBlank()) {
            return List.of();
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(appendQueryParameter(endpoint, "q", query)))
                .timeout(Duration.ofMillis(SearchProviderSupport.timeoutMillis(request)))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("search_http_" + response.statusCode());
        }
        return SearchResponseParser.parse(objectMapper, response.body(), "Brave");
    }

    private String appendQueryParameter(String url, String name, String value) {
        if (url == null || url.isBlank() || name == null || name.isBlank() || value == null || value.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }
}

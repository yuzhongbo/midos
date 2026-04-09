package com.zhongbo.mindos.assistant.skill.search;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class SerperSearchProvider implements SearchProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    SerperSearchProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), new ObjectMapper().findAndRegisterModules());
    }

    SerperSearchProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SearchSourceConfig source) {
        return source != null && source.hasSerperEndpoint();
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
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(SearchProviderSupport.timeoutMillis(request)))
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("q", query)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("search_http_" + response.statusCode());
        }
        return SearchResponseParser.parse(objectMapper, response.body(), "Serper");
    }
}

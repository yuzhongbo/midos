package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.search.SearchSourceConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SearchToolAdapterChain {

    private final List<SearchToolAdapter> adapters;

    public SearchToolAdapterChain(List<SearchToolAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    public Optional<SearchToolBinding> resolve(SearchSourceConfig source, Map<String, String> headers) {
        if (source == null || adapters.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> effectiveHeaders = mergeHeaders(source, headers);
        for (SearchToolAdapter adapter : adapters) {
            if (adapter != null && adapter.supports(source)) {
                Optional<SearchToolBinding> binding = adapter.adapt(source, effectiveHeaders);
                if (binding.isPresent()) {
                    return binding;
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, String> mergeHeaders(SearchSourceConfig source, Map<String, String> headers) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (source != null) {
            String apiKey = source.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                merged.put(source.resolvedApiKeyHeader(), apiKey.trim());
            }
        }
        if (headers != null && !headers.isEmpty()) {
            merged.putAll(headers);
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }
}


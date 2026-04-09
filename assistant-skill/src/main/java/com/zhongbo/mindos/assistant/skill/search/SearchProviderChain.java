package com.zhongbo.mindos.assistant.skill.search;

import java.util.List;

public final class SearchProviderChain {

    private final List<SearchProvider> providers;

    public SearchProviderChain(List<SearchProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public List<SearchResultItem> search(SearchSourceConfig source, SearchRequest request) throws Exception {
        if (source == null || request == null || providers.isEmpty()) {
            return List.of();
        }
        for (SearchProvider provider : providers) {
            if (provider != null && provider.supports(source)) {
                List<SearchResultItem> items = provider.search(source, request);
                return items == null ? List.of() : List.copyOf(items);
            }
        }
        return List.of();
    }
}

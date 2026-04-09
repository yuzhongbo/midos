package com.zhongbo.mindos.assistant.skill.search;

import java.util.List;

public final class SearchProviderRegistry {

    private SearchProviderRegistry() {
    }

    public static SearchProviderChain standard() {
        return new SearchProviderChain(List.of(
                new BraveSearchProvider(),
                new SerperSearchProvider(),
                new SerpApiSearchProvider()
        ));
    }
}

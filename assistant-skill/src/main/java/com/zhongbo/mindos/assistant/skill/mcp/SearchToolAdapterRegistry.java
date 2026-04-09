package com.zhongbo.mindos.assistant.skill.mcp;

import java.util.List;

public final class SearchToolAdapterRegistry {

    private SearchToolAdapterRegistry() {
    }

    public static SearchToolAdapterChain standard() {
        return new SearchToolAdapterChain(List.of(
                new BraveSearchProvider(),
                new SerperSearchProvider(),
                new SerpApiSearchProvider()
        ));
    }
}


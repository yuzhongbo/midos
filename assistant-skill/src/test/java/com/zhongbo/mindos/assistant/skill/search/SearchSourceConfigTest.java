package com.zhongbo.mindos.assistant.skill.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchSourceConfigTest {

    @Test
    void shouldParseSearchSourcesAndIgnoreInvalidEntries() {
        List<SearchSourceConfig> sources = SearchSourceConfig.parseList(
                "serper:https://google.serper.dev/search;news-url=https://google.serper.dev/news;api-key=serper-key," +
                        "serpapi:https://serpapi.com/search.json?engine=google_news;api-key=serpapi-key;api-key-header=Authorization," +
                        "invalid-entry"
        );

        assertEquals(2, sources.size());
        assertEquals("serper", sources.get(0).alias());
        assertEquals("https://google.serper.dev/search", sources.get(0).resolvedSearchUrl());
        assertEquals("https://google.serper.dev/news", sources.get(0).resolvedNewsUrl());
        assertTrue(sources.get(0).isSerperLike());
        assertEquals("X-API-KEY", sources.get(0).resolvedApiKeyHeader());

        assertEquals("serpapi", sources.get(1).alias());
        assertTrue(sources.get(1).isSerpApiLike());
        assertEquals("api_key", sources.get(1).resolvedApiKeyHeader());
        assertTrue(sources.get(1).hasApiKey());
    }

    @Test
    void shouldPreserveShortcutFactoriesForBraveAndSerper() {
        SearchSourceConfig brave = SearchSourceConfig.brave(
                "BraveSearch",
                "https://api.search.brave.com/res/v1/web/search",
                "brave-key",
                ""
        );
        SearchSourceConfig serper = SearchSourceConfig.serper(
                "CustomSerper",
                "https://google.serper.dev/search",
                "https://google.serper.dev/news",
                "serper-key"
        );

        assertEquals("bravesearch", brave.alias());
        assertTrue(brave.isBraveLike());
        assertEquals("X-Subscription-Token", brave.resolvedApiKeyHeader());
        assertEquals("https://api.search.brave.com/res/v1/web/search", brave.resolvedMcpUrl());

        assertEquals("customserper", serper.alias());
        assertTrue(serper.isSerperLike());
        assertEquals("https://google.serper.dev/search", serper.resolvedMcpUrl());
        assertEquals("https://google.serper.dev/news", serper.resolvedNewsUrl());
        assertEquals("X-API-KEY", serper.resolvedApiKeyHeader());
    }

    @Test
    void shouldDetectSerpApiByUrlEvenWhenAliasIsCustom() {
        SearchSourceConfig serpApi = SearchSourceConfig.parseEntry(
                "precision:https://serpapi.com/search.json?engine=google_news;api-key=serpapi-key;api-key-header=Authorization"
        );

        assertNotNull(serpApi);
        assertEquals("precision", serpApi.alias());
        assertTrue(serpApi.isSerpApiLike());
        assertEquals("api_key", serpApi.resolvedApiKeyHeader());
        assertEquals("https://serpapi.com/search.json?engine=google_news", serpApi.resolvedSearchUrl());
        assertFalse(serpApi.resolvedNewsUrl().isBlank());
    }
}



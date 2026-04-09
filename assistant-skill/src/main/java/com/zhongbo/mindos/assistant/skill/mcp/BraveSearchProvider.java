package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.search.SearchSourceConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class BraveSearchProvider implements SearchToolAdapter {

    @Override
    public boolean supports(SearchSourceConfig source) {
        return source != null && source.hasBraveEndpoint();
    }

    @Override
    public Optional<SearchToolBinding> adapt(SearchSourceConfig source, Map<String, String> headers) {
        if (source == null) {
            return Optional.empty();
        }
        String url = source.resolvedMcpUrl();
        if (url.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> mergedHeaders = copyHeaders(headers);
        McpToolDefinition tool = new McpToolDefinition(
                source.alias(),
                url,
                "webSearch",
                "Brave latest web news search",
                mergedHeaders
        );
        return Optional.of(new SearchToolBinding(tool, new BraveSearchRestClient(), SearchRequestStyle.POST_JSON, "Brave", source.resolvedNewsUrl(), ""));
    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(headers));
    }
}




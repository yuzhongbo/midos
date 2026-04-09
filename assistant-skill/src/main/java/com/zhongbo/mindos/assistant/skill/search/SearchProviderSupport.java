package com.zhongbo.mindos.assistant.skill.search;

import java.net.URI;
import java.util.Locale;

final class SearchProviderSupport {

    private SearchProviderSupport() {
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    static String resolveApiKey(SearchSourceConfig source) {
        return source == null ? "" : firstNonBlank(source.apiKey());
    }

    static boolean containsQueryParameter(String url, String name) {
        if (url == null || url.isBlank() || name == null || name.isBlank()) {
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
                if (name.equalsIgnoreCase(key.trim())) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ex) {
            return url.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT) + "=");
        }
        return false;
    }

    static boolean hasConfiguredEndpoint(SearchSourceConfig source) {
        if (source == null) {
            return false;
        }
        return !firstNonBlank(source.resolvedNewsUrl(), source.resolvedSearchUrl()).isBlank();
    }

    static String requireQuery(SearchRequest request) {
        return request == null ? "" : firstNonBlank(request.query());
    }

    static int timeoutMillis(SearchRequest request) {
        return request == null ? 1000 : Math.max(1000, request.timeoutMs());
    }
}

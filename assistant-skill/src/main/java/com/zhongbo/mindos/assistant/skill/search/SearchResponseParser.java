package com.zhongbo.mindos.assistant.skill.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.skill.examples.util.TitleCleaner;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SearchResponseParser {

    private static final Logger LOGGER = Logger.getLogger(SearchResponseParser.class.getName());
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private SearchResponseParser() {
    }

    @SuppressWarnings("unchecked")
    static List<SearchResultItem> parse(ObjectMapper objectMapper, String body, String sourceLabel) {
        if (objectMapper == null || body == null || body.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            String[] candidates = new String[]{"organic_results", "news_results", "top_stories", "inline_news_results", "news", "organic", "articles", "items", "results"};
            for (String key : candidates) {
                Object value = payload.get(key);
                if (value instanceof List<?> list && !list.isEmpty()) {
                    List<SearchResultItem> out = new ArrayList<>();
                    for (Object entry : list) {
                        if (!(entry instanceof Map<?, ?> rawMap)) {
                            continue;
                        }
                        Map<String, Object> map = (Map<String, Object>) rawMap;
                        String title = text(map.getOrDefault("title", map.get("headline")));
                        if (title.isBlank()) {
                            title = text(map.getOrDefault("name", ""));
                        }
                        String link = text(map.getOrDefault("link", map.get("url")));
                        String summary = text(map.getOrDefault("snippet", map.get("description")));
                        String source = text(map.getOrDefault("source", sourceLabel == null || sourceLabel.isBlank() ? "search" : sourceLabel));
                        String published = text(map.getOrDefault("publishedAt", map.get("published")));
                        if (!title.isBlank()) {
                            out.add(new SearchResultItem(
                                    TitleCleaner.cleanTitle(title),
                                    link,
                                    summary,
                                    parseTime(published),
                                    source
                            ));
                        }
                    }
                    if (!out.isEmpty()) {
                        return List.copyOf(out);
                    }
                }
            }
            if (payload.get("news") instanceof Map<?, ?> newsMap) {
                Object items = newsMap.get("value");
                if (items instanceof List<?> list) {
                    return parse(objectMapper, objectMapper.writeValueAsString(Map.of("items", list)), sourceLabel);
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "search response parse failed", ex);
        }
        return List.of();
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.EPOCH;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(value, RFC1123).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return Instant.EPOCH;
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? "" : text;
    }
}

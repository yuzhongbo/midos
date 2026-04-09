package com.zhongbo.mindos.assistant.skill.search;

import java.time.Instant;

public record SearchResultItem(String title,
                               String link,
                               String summary,
                               Instant publishedAt,
                               String source) {

    public SearchResultItem {
        title = title == null ? "" : title.trim();
        link = link == null ? "" : link.trim();
        summary = summary == null ? "" : summary.trim();
        publishedAt = publishedAt == null ? Instant.EPOCH : publishedAt;
        source = source == null ? "" : source.trim();
    }
}

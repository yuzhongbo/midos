package com.zhongbo.mindos.assistant.api.news;

import java.time.Instant;
import java.util.List;

record NewsPushResult(
        boolean delivered,
        int fetchedCount,
        int usedCount,
        String channel,
        String error,
        String summary,
        String message,
        Instant timestamp,
        List<NewsItem> items
) {
}

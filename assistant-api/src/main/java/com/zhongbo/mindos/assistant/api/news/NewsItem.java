package com.zhongbo.mindos.assistant.api.news;

import java.time.Instant;

record NewsItem(String title, String link, String summary, Instant publishedAt, String source) {
}

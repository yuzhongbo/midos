package com.zhongbo.mindos.assistant.skill.search;

public record SearchRequest(String query, int timeoutMs) {

    public SearchRequest {
        query = query == null ? "" : query.trim();
        timeoutMs = Math.max(1000, timeoutMs);
    }
}

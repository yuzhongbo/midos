package com.zhongbo.mindos.assistant.memory.graph;

import java.util.Set;

public record GraphMemoryQuery(Set<String> seedNodeIds,
                               Set<String> relationFilter,
                               int maxDepth,
                               int limit,
                               String keyword) {

    public GraphMemoryQuery {
        seedNodeIds = seedNodeIds == null ? Set.of() : Set.copyOf(seedNodeIds);
        relationFilter = relationFilter == null ? Set.of() : Set.copyOf(relationFilter);
        maxDepth = Math.max(0, maxDepth);
        limit = Math.max(1, limit);
        keyword = keyword == null ? "" : keyword.trim();
    }

    public static GraphMemoryQuery keyword(String keyword, int limit) {
        return new GraphMemoryQuery(Set.of(), Set.of(), 0, limit, keyword);
    }

    public static GraphMemoryQuery traverse(Set<String> seedNodeIds, int maxDepth, int limit) {
        return new GraphMemoryQuery(seedNodeIds, Set.of(), maxDepth, limit, "");
    }
}

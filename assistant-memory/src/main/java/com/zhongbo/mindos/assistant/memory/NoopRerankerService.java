package com.zhongbo.mindos.assistant.memory;

public class NoopRerankerService implements RerankerService {

    @Override
    public java.util.List<HybridSearchService.HybridSearchResult> rerank(String query,
                                                                          java.util.List<HybridSearchService.HybridSearchResult> candidates,
                                                                          int limit) {
        return candidates;
    }
}

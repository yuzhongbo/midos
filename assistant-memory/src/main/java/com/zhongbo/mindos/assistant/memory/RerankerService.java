package com.zhongbo.mindos.assistant.memory;

import java.util.List;

public interface RerankerService {

    List<HybridSearchService.HybridSearchResult> rerank(String query,
                                                        List<HybridSearchService.HybridSearchResult> candidates,
                                                        int limit);
}

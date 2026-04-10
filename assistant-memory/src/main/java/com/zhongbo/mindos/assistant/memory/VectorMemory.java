package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;

import java.util.List;

public interface VectorMemory {

    List<Double> embed(String text);

    List<VectorSearchResult> search(String userId, String query, int topK);

    default List<VectorSearchResult> search(String query, int topK) {
        return search("", query, topK);
    }
}

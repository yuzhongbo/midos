package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.VectorMemoryRecord;
import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;

import java.util.List;

public interface VectorMemoryRepository {

    // Provider-backed implementation can target Milvus, Weaviate, or FAISS wrapper.
    void save(VectorMemoryRecord record);

    List<VectorSearchResult> searchTopK(String userId, List<Double> queryEmbedding, int topK);
}


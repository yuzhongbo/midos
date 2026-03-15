package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.VectorMemoryRecord;
import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryVectorMemoryRepository implements VectorMemoryRepository {

    private final Map<String, List<VectorMemoryRecord>> storeByUser = new ConcurrentHashMap<>();

    @Override
    public void save(VectorMemoryRecord record) {
        storeByUser.computeIfAbsent(record.userId(), key -> new ArrayList<>()).add(record);
    }

    @Override
    public List<VectorSearchResult> searchTopK(String userId, List<Double> queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.isEmpty() || topK <= 0) {
            return List.of();
        }

        return storeByUser.getOrDefault(userId, List.of()).stream()
                .map(record -> new VectorSearchResult(record, cosineSimilarity(queryEmbedding, record.embedding())))
                .sorted(Comparator.comparing(VectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        int size = Math.min(left.size(), right.size());
        if (size == 0) {
            return 0.0;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}


package com.zhongbo.mindos.assistant.memory;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class EmbeddingSimilarityReranker implements RerankerService {

    private final EmbeddingService embeddingService;
    private final MemoryRuntimeProperties properties;

    public EmbeddingSimilarityReranker(EmbeddingService embeddingService,
                                       MemoryRuntimeProperties properties) {
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    @Override
    public List<HybridSearchService.HybridSearchResult> rerank(String query,
                                                               List<HybridSearchService.HybridSearchResult> candidates,
                                                               int limit) {
        if (!properties.getRerank().isEnabled() || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        int maxCandidates = Math.max(1, properties.getRerank().getMaxCandidates());
        double weight = properties.getRerank().getScoreWeight();
        float[] queryEmbedding = embeddingService.embed(query);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size() && i < maxCandidates; i++) {
            HybridSearchService.HybridSearchResult candidate = candidates.get(i);
            float[] docEmbedding = embeddingService.embed(candidate.content());
            double similarity = cosine(queryEmbedding, docEmbedding);
            double combined = weight * similarity + (1.0 - weight) * candidate.finalScore();
            scored.add(new Scored(candidate, combined));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<HybridSearchService.HybridSearchResult> reranked = new ArrayList<>(candidates.size());
        for (Scored value : scored) {
            if (reranked.size() >= limit) {
                break;
            }
            reranked.add(value.result());
        }
        // keep remaining (if any) in original order to preserve determinism
        for (HybridSearchService.HybridSearchResult candidate : candidates) {
            if (reranked.size() >= limit) {
                break;
            }
            if (!reranked.contains(candidate)) {
                reranked.add(candidate);
            }
        }
        return reranked;
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0d;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0.0d || normB <= 0.0d) {
            return 0.0d;
        }
        return dot / Math.sqrt(normA * normB);
    }

    private record Scored(HybridSearchService.HybridSearchResult result, double score) {
    }
}

package com.zhongbo.mindos.assistant.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Default sparse scorer for long-term memory retrieval.
 * BM25 gives stable local lexical relevance and works well as the sparse side
 * of the hybrid search blend.
 */
@Service
public class Bm25LexicalSearchScorer implements LexicalSearchScorer {

    private static final double EPSILON = 1e-9;

    private final MemoryRuntimeProperties properties;

    @Autowired
    public Bm25LexicalSearchScorer(MemoryRuntimeProperties properties) {
        this.properties = properties;
    }

    public Bm25LexicalSearchScorer() {
        this(MemoryRuntimeProperties.fromSystemProperties());
    }

    @Override
    public double score(Set<String> queryTokens, MemorySearchDocument document, MemorySearchCorpus corpus) {
        if (queryTokens == null || queryTokens.isEmpty() || document == null || corpus == null || corpus.documentCount() <= 0) {
            return 0.0d;
        }
        double k1 = properties.getSearch().getHybrid().getK1();
        double b = properties.getSearch().getHybrid().getB();
        double avgLength = Math.max(1.0d, corpus.averageDocumentLength());
        double documentLength = Math.max(1.0d, document.length());
        double score = 0.0d;
        for (String token : queryTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            int frequency = document.termFrequency().getOrDefault(token, 0);
            if (frequency <= 0) {
                continue;
            }
            int documentFrequency = corpus.documentFrequencyByToken().getOrDefault(token, 0);
            double idf = Math.log1p((corpus.documentCount() - documentFrequency + 0.5d) / (documentFrequency + 0.5d + EPSILON));
            double denominator = frequency + k1 * (1.0d - b + b * (documentLength / avgLength));
            score += idf * ((frequency * (k1 + 1.0d)) / Math.max(EPSILON, denominator));
        }
        return Math.max(0.0d, score);
    }
}

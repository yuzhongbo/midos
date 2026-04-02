package com.zhongbo.mindos.assistant.memory;

import java.util.Map;
import java.util.Set;

/**
 * Boundary for lexical ranking so the search pipeline can evolve from BM25
 * to richer sparse retrieval without changing callers.
 */
public interface LexicalSearchScorer {

    double score(Set<String> queryTokens, MemorySearchDocument document, MemorySearchCorpus corpus);
}

record MemorySearchDocument(String text,
                            Set<String> tokens,
                            Map<String, Integer> termFrequency) {

    int length() {
        return Math.max(1, tokens.size());
    }
}

record MemorySearchCorpus(int documentCount,
                          double averageDocumentLength,
                          Map<String, Integer> documentFrequencyByToken) {
}

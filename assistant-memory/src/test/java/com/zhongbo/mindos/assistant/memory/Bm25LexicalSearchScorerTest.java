package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25LexicalSearchScorerTest {

    @Test
    void shouldHandleIdfEdgeCases() {
        Bm25LexicalSearchScorer scorer = new Bm25LexicalSearchScorer();
        MemorySearchDocument document = new MemorySearchDocument(
                "alpha beta",
                Set.of("alpha", "beta"),
                Map.of("alpha", 1, "beta", 1)
        );

        double ubiquitousScore = scorer.score(
                Set.of("alpha"),
                document,
                new MemorySearchCorpus(2, 2.0, Map.of("alpha", 2))
        );
        double missingDfScore = scorer.score(
                Set.of("alpha"),
                document,
                new MemorySearchCorpus(2, 2.0, Map.of())
        );

        assertTrue(ubiquitousScore >= 0.0);
        assertTrue(missingDfScore > ubiquitousScore);
    }
}

package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSearchServiceTest {

    @Test
    void shouldMergeBm25AndVectorResultsWithoutDuplicates() throws IOException {
        Map<String, float[]> embeddings = Map.of(
                "alpha rollout", new float[]{1.0f, 0.0f},
                "launch checklist", new float[]{0.0f, 1.0f},
                "alpha launch plan", new float[]{0.8f, 0.6f},
                "alpha launch", new float[]{0.8f, 0.6f}
        );
        EmbeddingService embeddingService = text -> embeddings.getOrDefault(text, new float[]{0.0f, 0.0f});

        try (HybridSearchService service = new HybridSearchService(embeddingService)) {
            service.upsert("doc-1", "alpha rollout", embeddings.get("alpha rollout"));
            service.upsert("doc-2", "launch checklist", embeddings.get("launch checklist"));
            service.upsert("doc-3", "alpha launch plan", embeddings.get("alpha launch plan"));

            List<HybridSearchService.HybridSearchResult> results = service.search("alpha launch", 3);

            assertEquals(3, results.size());
            assertEquals(3, results.stream().map(HybridSearchService.HybridSearchResult::documentId).distinct().count());
            assertEquals("doc-3", results.get(0).documentId());

            Map<String, HybridSearchService.HybridSearchResult> byId = results.stream()
                    .collect(Collectors.toMap(HybridSearchService.HybridSearchResult::documentId, Function.identity()));
            assertTrue(byId.get("doc-1").bm25Score() > 0.0d);
            assertTrue(byId.get("doc-2").vectorScore() > 0.0d);
            assertTrue(byId.get("doc-3").finalScore() >= byId.get("doc-1").finalScore());
        }
    }

    @Test
    void shouldRespectTopKAndAllowDocumentUpdates() throws IOException {
        EmbeddingService embeddingService = text -> switch (text) {
            case "release" -> new float[]{1.0f, 0.0f};
            case "release note" -> new float[]{1.0f, 0.0f};
            case "release checklist" -> new float[]{0.9f, 0.1f};
            case "team lunch" -> new float[]{0.0f, 1.0f};
            default -> new float[]{0.0f, 0.0f};
        };

        try (HybridSearchService service = new HybridSearchService(embeddingService)) {
            service.upsert("doc-1", "release note");
            service.upsert("doc-2", "release checklist");
            service.upsert("doc-3", "team lunch");
            service.upsert("doc-2", "release checklist");

            List<HybridSearchService.HybridSearchResult> results = service.search("release", 2);

            assertEquals(2, results.size());
            assertEquals(List.of("doc-1", "doc-2"),
                    results.stream().map(HybridSearchService.HybridSearchResult::documentId).toList());
        }
    }

    @Test
    void shouldRerankWithEmbeddingSimilarity() throws IOException {
        EmbeddingService embeddingService = text -> switch (text) {
            case "query" -> new float[]{1.0f, 0.0f};
            case "doc strong" -> new float[]{0.9f, 0.1f};
            case "doc weak" -> new float[]{0.1f, 0.9f};
            default -> new float[]{0.0f, 0.0f};
        };
        MemoryRuntimeProperties properties = new MemoryRuntimeProperties();
        properties.getRerank().setEnabled(true);
        properties.getRerank().setMaxCandidates(2);
        properties.getRerank().setScoreWeight(1.0);

        RerankerService reranker = new EmbeddingSimilarityReranker(embeddingService, properties);

        try (HybridSearchService service = new HybridSearchService(embeddingService,
                0.5, 0.5, 4, reranker)) {
            service.upsert("doc-1", "doc weak");
            service.upsert("doc-2", "doc strong");

            List<HybridSearchService.HybridSearchResult> results = service.search("query", 2);

            assertEquals(2, results.size());
            assertEquals("doc-2", results.get(0).documentId());
        }
    }
}

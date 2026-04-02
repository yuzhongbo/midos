package com.zhongbo.mindos.assistant.memory;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxEmbeddingServiceTest {

    @Test
    void shouldBatchCacheAndDeduplicateEmbeddings() {
        MemoryRuntimeProperties properties = new MemoryRuntimeProperties();
        properties.getEmbedding().getOnnx().setEnabled(true);
        properties.getEmbedding().getOnnx().setDimensions(4);
        properties.getEmbedding().getOnnx().setBatchSize(2);
        properties.getEmbedding().getOnnx().getCache().setMaximumSize(128);

        CountingRunner runner = new CountingRunner();
        OnnxEmbeddingService service = new OnnxEmbeddingService(
                new MemoryConsolidationService(),
                properties,
                Caffeine.newBuilder().maximumSize(128).build(),
                runner
        );

        List<float[]> first = service.embedBatch(List.of("Alpha", "Alpha", "Beta", "Gamma"));
        List<float[]> second = service.embedBatch(List.of("Alpha", "Beta"));

        assertEquals(2, runner.requests.size());
        assertEquals(List.of("Alpha", "Beta"), runner.requests.get(0));
        assertEquals(List.of("Gamma"), runner.requests.get(1));
        assertArrayEquals(first.get(0), first.get(1));
        assertArrayEquals(first.get(0), second.get(0));
        assertArrayEquals(first.get(2), second.get(1));
    }

    @Test
    void shouldReturnZeroVectorForBlankText() {
        MemoryRuntimeProperties properties = new MemoryRuntimeProperties();
        properties.getEmbedding().getOnnx().setEnabled(true);
        properties.getEmbedding().getOnnx().setDimensions(3);

        OnnxEmbeddingService service = new OnnxEmbeddingService(
                new MemoryConsolidationService(),
                properties,
                Caffeine.newBuilder().maximumSize(16).build(),
                new OnnxEmbeddingService.BatchInferenceRunner() {
                    @Override
                    public List<float[]> embedBatch(List<String> texts) {
                        return texts.stream().map(text -> new float[]{1f, 2f, 3f}).toList();
                    }

                    @Override
                    public void close() {
                    }
                }
        );

        float[] vector = service.embed("   ");

        assertEquals(3, vector.length);
        for (float value : vector) {
            assertEquals(0.0f, value);
        }
    }

    private static final class CountingRunner implements OnnxEmbeddingService.BatchInferenceRunner {
        private final List<List<String>> requests = new ArrayList<>();

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            requests.add(List.copyOf(texts));
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(new float[]{
                        text.length(),
                        text.isEmpty() ? 0 : text.charAt(0),
                        Math.max(1, text.hashCode() & 0xFF),
                        1.0f
                });
            }
            return vectors;
        }

        @Override
        public void close() {
        }
    }
}

package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class HashingEmbeddingServiceAdapterTest {

    @Test
    void shouldConvertHashingEmbeddingVectorToFloatArray() {
        HashingLocalEmbeddingService hashingService = new HashingLocalEmbeddingService(null, null) {
            @Override
            public List<Double> embed(String text) {
                return List.of(0.1d, -0.2d, 0.3d);
            }
        };

        HashingEmbeddingServiceAdapter adapter = new HashingEmbeddingServiceAdapter(hashingService);
        float[] actual = adapter.embed("hello");

        assertArrayEquals(new float[]{0.1f, -0.2f, 0.3f}, actual, 0.0001f);
    }

    @Test
    void shouldReturnEmptyArrayWhenHashingEmbeddingIsEmpty() {
        HashingLocalEmbeddingService hashingService = new HashingLocalEmbeddingService(null, null) {
            @Override
            public List<Double> embed(String text) {
                return List.of();
            }
        };

        HashingEmbeddingServiceAdapter adapter = new HashingEmbeddingServiceAdapter(hashingService);
        float[] actual = adapter.embed("empty");

        assertArrayEquals(new float[0], actual);
    }
}


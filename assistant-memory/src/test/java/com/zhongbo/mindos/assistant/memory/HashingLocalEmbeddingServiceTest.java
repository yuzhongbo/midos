package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HashingLocalEmbeddingServiceTest {

    @Test
    void shouldGenerateStableBigramAwareEmbeddings() {
        String oldEnabled = System.getProperty("mindos.memory.embedding.local.enabled");
        String oldDimensions = System.getProperty("mindos.memory.embedding.local.dimensions");
        try {
            System.setProperty("mindos.memory.embedding.local.enabled", "true");
            System.setProperty("mindos.memory.embedding.local.dimensions", "16");

            HashingLocalEmbeddingService service = new HashingLocalEmbeddingService(new MemoryConsolidationService());
            List<Double> first = service.embed("alpha beta");
            List<Double> second = service.embed("alpha beta");
            List<Double> reversed = service.embed("beta alpha");

            assertFalse(first.isEmpty());
            assertEquals(first, second);
            assertFalse(first.equals(reversed));
        } finally {
            restoreProperty("mindos.memory.embedding.local.enabled", oldEnabled);
            restoreProperty("mindos.memory.embedding.local.dimensions", oldDimensions);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}

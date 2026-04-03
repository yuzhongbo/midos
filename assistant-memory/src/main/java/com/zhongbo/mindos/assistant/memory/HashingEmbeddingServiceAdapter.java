package com.zhongbo.mindos.assistant.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "mindos.memory.embedding.onnx", name = "enabled", havingValue = "false", matchIfMissing = true)
class HashingEmbeddingServiceAdapter implements EmbeddingService {

    private final HashingLocalEmbeddingService hashingLocalEmbeddingService;

    HashingEmbeddingServiceAdapter(HashingLocalEmbeddingService hashingLocalEmbeddingService) {
        this.hashingLocalEmbeddingService = hashingLocalEmbeddingService;
    }

    @Override
    public float[] embed(String text) {
        List<Double> vector = hashingLocalEmbeddingService.embed(text);
        if (vector == null || vector.isEmpty()) {
            return new float[0];
        }
        float[] converted = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            converted[i] = vector.get(i) == null ? 0.0f : vector.get(i).floatValue();
        }
        return converted;
    }
}


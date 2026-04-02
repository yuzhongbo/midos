package com.zhongbo.mindos.assistant.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Primary
@ConditionalOnBean(EmbeddingService.class)
public class OnnxLocalEmbeddingServiceAdapter implements LocalEmbeddingService {

    private final EmbeddingService embeddingService;

    public OnnxLocalEmbeddingServiceAdapter(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public List<Double> embed(String text) {
        float[] vector = embeddingService.embed(text);
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Double> converted = new ArrayList<>(vector.length);
        for (float value : vector) {
            converted.add((double) value);
        }
        return List.copyOf(converted);
    }
}

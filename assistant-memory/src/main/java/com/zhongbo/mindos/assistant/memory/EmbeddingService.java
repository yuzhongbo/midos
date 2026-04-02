package com.zhongbo.mindos.assistant.memory;

import java.util.List;

public interface EmbeddingService {

    float[] embed(String text);

    default List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream().map(this::embed).toList();
    }
}

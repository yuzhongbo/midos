package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.VectorMemoryRecord;
import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class VectorMemoryService implements VectorMemory {

    private final VectorMemoryRepository vectorMemoryRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VectorMemoryService(VectorMemoryRepository vectorMemoryRepository, EmbeddingService embeddingService) {
        this.vectorMemoryRepository = vectorMemoryRepository;
        this.embeddingService = embeddingService;
    }

    public void addKnowledgeEmbedding(String userId,
                                      String content,
                                      List<Double> embedding,
                                      Map<String, Object> metadata) {
        vectorMemoryRepository.save(VectorMemoryRecord.of(userId, content, embedding, metadata));
    }

    public void addKnowledgeEmbeddingJson(String userId,
                                          String content,
                                          List<Double> embedding,
                                          String metadataJson) {
        vectorMemoryRepository.save(VectorMemoryRecord.of(userId, content, embedding, fromMetadataJson(metadataJson)));
    }

    public List<VectorSearchResult> searchTopK(String userId, List<Double> queryEmbedding, int topK) {
        return vectorMemoryRepository.searchTopK(userId, queryEmbedding, topK);
    }

    @Override
    public List<Double> embed(String text) {
        float[] vector = embeddingService == null ? null : embeddingService.embed(text);
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<Double> embedded = new java.util.ArrayList<>(vector.length);
        for (float value : vector) {
            embedded.add((double) value);
        }
        return List.copyOf(embedded);
    }

    @Override
    public List<VectorSearchResult> search(String userId, String query, int topK) {
        return searchTopK(userId, embed(query), topK);
    }

    public String toMetadataJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize metadata to JSON", e);
        }
    }

    public Map<String, Object> fromMetadataJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse metadata JSON", e);
        }
    }
}

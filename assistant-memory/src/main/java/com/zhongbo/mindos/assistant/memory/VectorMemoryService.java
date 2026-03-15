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
public class VectorMemoryService {

    private final VectorMemoryRepository vectorMemoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VectorMemoryService(VectorMemoryRepository vectorMemoryRepository) {
        this.vectorMemoryRepository = vectorMemoryRepository;
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


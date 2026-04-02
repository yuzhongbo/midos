package com.zhongbo.mindos.assistant.memory;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Unified multi-layer memory payload used by {@link MemoryStore} implementations.
 * Metadata is represented as a JSON-friendly map so the in-memory default stays
 * lightweight while remaining compatible with future JDBC/JSON persistence.
 */
public record MemoryRecord(
        String id,
        String userId,
        String content,
        MemoryLayer layer,
        float[] embedding,
        Map<String, Object> metadata,
        double confidence,
        Instant createTime,
        Instant updateTime
) {

    public MemoryRecord {
        id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        userId = userId == null ? "" : userId.trim();
        content = content == null ? "" : content.trim();
        layer = layer == null ? MemoryLayer.SEMANTIC : layer;
        embedding = embedding == null ? new float[0] : Arrays.copyOf(embedding, embedding.length);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        confidence = Double.isFinite(confidence) ? Math.max(0.0d, Math.min(1.0d, confidence)) : 0.0d;
        Instant now = Instant.now();
        createTime = createTime == null ? now : createTime;
        updateTime = updateTime == null ? createTime : updateTime;
    }

    public static MemoryRecord of(String userId, String content, MemoryLayer layer) {
        return new MemoryRecord(null, userId, content, layer, null, Map.of(), 1.0d, null, null);
    }

    @Override
    public float[] embedding() {
        return Arrays.copyOf(embedding, embedding.length);
    }

    public MemoryRecord withLayer(MemoryLayer targetLayer) {
        return new MemoryRecord(id, userId, content, targetLayer, embedding, metadata, confidence, createTime, updateTime);
    }

    public MemoryRecord withMetadata(Map<String, Object> newMetadata) {
        return new MemoryRecord(id, userId, content, layer, embedding, newMetadata, confidence, createTime, updateTime);
    }

    public MemoryRecord touch(Instant now) {
        return new MemoryRecord(id, userId, content, layer, embedding, metadata, confidence, createTime, now);
    }
}

package com.zhongbo.mindos.assistant.memory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Working memory stores the active session/task context and overwrites by
 * session/topic so the latest task state is always easy to retrieve.
 */
@Service
public class WorkingMemoryService implements MemoryStore {

    private static final String DEFAULT_SESSION = "default";
    private static final String DEFAULT_TOPIC = "default";

    private final Map<String, Map<String, MemoryRecord>> recordsByUser = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord record) {
        if (record == null || record.userId().isBlank()) {
            return;
        }
        String key = compositeKey(record.metadata());
        MemoryRecord normalized = new MemoryRecord(
                record.id(),
                record.userId(),
                record.content(),
                MemoryLayer.WORKING,
                record.embedding(),
                record.metadata(),
                record.confidence(),
                record.createTime(),
                Instant.now()
        );
        recordsByUser.computeIfAbsent(normalized.userId(), ignored -> new ConcurrentHashMap<>()).put(key, normalized);
    }

    @Override
    public List<MemoryRecord> query(MemoryQuery query) {
        if (query == null || query.userId().isBlank()) {
            return List.of();
        }
        return recordsByUser.getOrDefault(query.userId(), Map.of()).values().stream()
                .filter(record -> query.matchesContent(record.content()))
                .filter(record -> query.sessionId().isBlank() || query.sessionId().equals(metadataText(record.metadata(), "sessionId")))
                .filter(record -> query.topic().isBlank() || query.topic().equals(metadataText(record.metadata(), "topic")))
                .sorted(Comparator.comparing(MemoryRecord::updateTime).reversed())
                .limit(query.limit())
                .toList();
    }

    public void saveSessionContext(String userId, String sessionId, String topic, String content, double confidence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId == null || sessionId.isBlank() ? DEFAULT_SESSION : sessionId.trim());
        metadata.put("topic", topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic.trim());
        save(new MemoryRecord(null, userId, content, MemoryLayer.WORKING, null, metadata, confidence, null, null));
    }

    private String compositeKey(Map<String, Object> metadata) {
        return metadataText(metadata, "sessionId", DEFAULT_SESSION) + "::" + metadataText(metadata, "topic", DEFAULT_TOPIC);
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        return metadataText(metadata, key, "");
    }

    private String metadataText(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }
}

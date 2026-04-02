package com.zhongbo.mindos.assistant.memory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fact memory keeps durable subject-predicate-object triples.
 */
@Service
public class FactMemoryService implements MemoryStore {

    private final Map<String, List<MemoryRecord>> factsByUser = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord record) {
        if (record == null || record.userId().isBlank()) {
            return;
        }
        MemoryRecord normalized = normalizeFactRecord(record);
        factsByUser.computeIfAbsent(normalized.userId(), ignored -> new CopyOnWriteArrayList<>()).add(normalized);
    }

    @Override
    public List<MemoryRecord> query(MemoryQuery query) {
        if (query == null || query.userId().isBlank()) {
            return List.of();
        }
        return factsByUser.getOrDefault(query.userId(), List.of()).stream()
                .filter(record -> query.matchesContent(record.content()))
                .filter(record -> metadataMatches(record.metadata(), "subject", query.metadataText("subject")))
                .filter(record -> metadataMatches(record.metadata(), "predicate", query.metadataText("predicate")))
                .filter(record -> metadataMatches(record.metadata(), "object", query.metadataText("object")))
                .sorted(Comparator.comparing(MemoryRecord::updateTime).reversed())
                .limit(query.limit())
                .toList();
    }

    public void saveTriple(String userId, String subject, String predicate, String object, double confidence) {
        Map<String, Object> metadata = Map.of(
                "subject", subject == null ? "" : subject.trim(),
                "predicate", predicate == null ? "" : predicate.trim(),
                "object", object == null ? "" : object.trim()
        );
        String content = tripleText(metadata);
        save(new MemoryRecord(null, userId, content, MemoryLayer.FACT, null, metadata, confidence, null, null));
    }

    private MemoryRecord normalizeFactRecord(MemoryRecord record) {
        Map<String, Object> metadata = record.metadata();
        String content = record.content().isBlank() ? tripleText(metadata) : record.content();
        return new MemoryRecord(
                record.id(),
                record.userId(),
                content,
                MemoryLayer.FACT,
                record.embedding(),
                metadata,
                record.confidence(),
                record.createTime(),
                Instant.now()
        );
    }

    private String tripleText(Map<String, Object> metadata) {
        String subject = metadataText(metadata, "subject");
        String predicate = metadataText(metadata, "predicate");
        String object = metadataText(metadata, "object");
        return (subject + " " + predicate + " " + object).trim();
    }

    private boolean metadataMatches(Map<String, Object> metadata, String key, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equalsIgnoreCase(metadataText(metadata, key));
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}

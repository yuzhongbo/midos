package com.zhongbo.mindos.assistant.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Buffer memory keeps the most recent raw messages without summarization.
 */
@Service
public class BufferMemoryService implements MemoryStore {

    private final int maxMessagesPerUser;
    private final Map<String, Deque<MemoryRecord>> recordsByUser = new ConcurrentHashMap<>();

    public BufferMemoryService(@Value("${mindos.memory.multilayer.buffer.max-messages:20}") int maxMessagesPerUser) {
        this.maxMessagesPerUser = Math.max(1, maxMessagesPerUser);
    }

    @Override
    public void save(MemoryRecord record) {
        if (record == null || record.userId().isBlank()) {
            return;
        }
        MemoryRecord normalized = new MemoryRecord(
                record.id(),
                record.userId(),
                record.content(),
                MemoryLayer.BUFFER,
                record.embedding(),
                record.metadata(),
                record.confidence(),
                record.createTime(),
                Instant.now()
        );
        Deque<MemoryRecord> deque = recordsByUser.computeIfAbsent(normalized.userId(), ignored -> new ConcurrentLinkedDeque<>());
        deque.addLast(normalized);
        while (deque.size() > maxMessagesPerUser) {
            deque.pollFirst();
        }
    }

    @Override
    public List<MemoryRecord> query(MemoryQuery query) {
        if (query == null || query.userId().isBlank()) {
            return List.of();
        }
        Deque<MemoryRecord> deque = recordsByUser.getOrDefault(query.userId(), new ConcurrentLinkedDeque<>());
        List<MemoryRecord> results = new ArrayList<>();
        deque.descendingIterator().forEachRemaining(results::add);
        return results.stream()
                .filter(record -> query.matchesContent(record.content()))
                .filter(record -> query.sessionId().isBlank() || query.sessionId().equals(metadataText(record.metadata(), "sessionId")))
                .sorted(Comparator.comparing(MemoryRecord::updateTime).reversed())
                .limit(query.limit())
                .toList();
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}

package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class InMemoryCentralMemoryRepository implements CentralMemoryRepository {

    private final Map<String, AtomicLong> cursorsByUser = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryEvent>> eventsByUser = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenEventIdsByUser = new ConcurrentHashMap<>();

    @Override
    public MemoryAppendResult appendEpisodic(String userId, ConversationTurn turn, String eventId) {
        return appendEvent(userId, turn, null, null, eventId);
    }

    @Override
    public MemoryAppendResult appendSemantic(String userId, SemanticMemoryEntry entry, String eventId) {
        return appendEvent(userId, null, entry, null, eventId);
    }

    @Override
    public MemoryAppendResult appendProcedural(String userId, ProceduralMemoryEntry entry, String eventId) {
        return appendEvent(userId, null, null, entry, eventId);
    }

    @Override
    public MemorySyncSnapshot fetchSince(String userId, long sinceCursorExclusive, int limit) {
        if (limit <= 0) {
            return MemorySyncSnapshot.empty(sinceCursorExclusive);
        }

        List<MemoryEvent> selected = eventsByUser.getOrDefault(userId, List.of()).stream()
                .filter(event -> event.cursor > sinceCursorExclusive)
                .sorted(Comparator.comparingLong(event -> event.cursor))
                .limit(limit)
                .toList();

        List<ConversationTurn> episodic = new ArrayList<>();
        List<SemanticMemoryEntry> semantic = new ArrayList<>();
        List<ProceduralMemoryEntry> procedural = new ArrayList<>();

        long cursor = sinceCursorExclusive;
        for (MemoryEvent event : selected) {
            if (event.episodic != null) {
                episodic.add(event.episodic);
            }
            if (event.semantic != null) {
                semantic.add(event.semantic);
            }
            if (event.procedural != null) {
                procedural.add(event.procedural);
            }
            cursor = Math.max(cursor, event.cursor);
        }

        return new MemorySyncSnapshot(cursor, episodic, semantic, procedural);
    }

    private MemoryAppendResult appendEvent(String userId,
                                           ConversationTurn episodic,
                                           SemanticMemoryEntry semantic,
                                           ProceduralMemoryEntry procedural,
                                           String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            Set<String> seenEventIds = seenEventIdsByUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet());
            if (!seenEventIds.add(eventId)) {
                long cursor = cursorsByUser.getOrDefault(userId, new AtomicLong(0L)).get();
                return new MemoryAppendResult(cursor, false);
            }
        }

        long cursor = cursorsByUser.computeIfAbsent(userId, key -> new AtomicLong(0L)).incrementAndGet();
        eventsByUser.computeIfAbsent(userId, key -> new ArrayList<>())
                .add(new MemoryEvent(cursor, episodic, semantic, procedural));
        return new MemoryAppendResult(cursor, true);
    }

    private record MemoryEvent(
            long cursor,
            ConversationTurn episodic,
            SemanticMemoryEntry semantic,
            ProceduralMemoryEntry procedural
    ) {
    }
}


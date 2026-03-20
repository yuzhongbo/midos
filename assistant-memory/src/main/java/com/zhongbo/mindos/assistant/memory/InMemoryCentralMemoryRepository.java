package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class InMemoryCentralMemoryRepository implements CentralMemoryRepository {

    private static final int DEFAULT_MAX_EVENTS_PER_USER = 10_000;

    private final Map<String, UserMemoryLog> logsByUser = new ConcurrentHashMap<>();
    private final int maxEventsPerUser;

    public InMemoryCentralMemoryRepository() {
        this.maxEventsPerUser = Integer.getInteger("mindos.memory.inmemory.max-events-per-user", DEFAULT_MAX_EVENTS_PER_USER);
    }

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

        UserMemoryLog log = logsByUser.get(userId);
        if (log == null) {
            return MemorySyncSnapshot.empty(sinceCursorExclusive);
        }

        List<MemoryEvent> selected;
        log.lock.readLock().lock();
        try {
            if (log.events.isEmpty()) {
                return MemorySyncSnapshot.empty(sinceCursorExclusive);
            }
            int startIndex = firstIndexGreaterThan(log.events, sinceCursorExclusive);
            if (startIndex >= log.events.size()) {
                return MemorySyncSnapshot.empty(sinceCursorExclusive);
            }
            int endIndex = Math.min(log.events.size(), startIndex + limit);
            selected = new ArrayList<>(log.events.subList(startIndex, endIndex));
        } finally {
            log.lock.readLock().unlock();
        }

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
        UserMemoryLog log = logsByUser.computeIfAbsent(userId, key -> new UserMemoryLog());
        log.lock.writeLock().lock();
        try {
            if (eventId != null && !eventId.isBlank()) {
                if (!log.seenEventIds.add(eventId)) {
                    return new MemoryAppendResult(log.cursor.get(), false);
                }
                log.eventIdOrder.addLast(eventId);
            }

            long cursor = log.cursor.incrementAndGet();
            log.events.add(new MemoryEvent(cursor, episodic, semantic, procedural));
            enforceRetention(log);
            return new MemoryAppendResult(cursor, true);
        } finally {
            log.lock.writeLock().unlock();
        }
    }

    private int firstIndexGreaterThan(List<MemoryEvent> events, long cursorExclusive) {
        int low = 0;
        int high = events.size() - 1;
        int answer = events.size();
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (events.get(mid).cursor > cursorExclusive) {
                answer = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return answer;
    }

    private void enforceRetention(UserMemoryLog log) {
        int safeLimit = Math.max(1, maxEventsPerUser);
        while (log.events.size() > safeLimit) {
            log.events.remove(0);
        }
        while (log.eventIdOrder.size() > safeLimit) {
            String oldest = log.eventIdOrder.removeFirst();
            log.seenEventIds.remove(oldest);
        }
    }

    private static final class UserMemoryLog {
        private final AtomicLong cursor = new AtomicLong(0L);
        private final List<MemoryEvent> events = new ArrayList<>();
        private final java.util.Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
        private final ArrayDeque<String> eventIdOrder = new ArrayDeque<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    }

    private record MemoryEvent(
            long cursor,
            ConversationTurn episodic,
            SemanticMemoryEntry semantic,
            ProceduralMemoryEntry procedural
    ) {
    }
}


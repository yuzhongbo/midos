package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * File-backed central repository for single-host deployments.
 *
 * Data format: one JSON event per line, one file per user.
 */
public class FileCentralMemoryRepository implements CentralMemoryRepository {

    private static final int DEFAULT_MAX_EVENTS_PER_USER = 10_000;

    private final Path baseDirectory;
    private final ObjectMapper objectMapper;
    private final Map<String, UserMemoryLog> logsByUser = new ConcurrentHashMap<>();
    private final int maxEventsPerUser;

    public FileCentralMemoryRepository(Path baseDirectory, ObjectMapper objectMapper) {
        this(baseDirectory, objectMapper, Integer.getInteger("mindos.memory.inmemory.max-events-per-user", DEFAULT_MAX_EVENTS_PER_USER));
    }

    public FileCentralMemoryRepository(Path baseDirectory, ObjectMapper objectMapper, int maxEventsPerUser) {
        this.baseDirectory = baseDirectory;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.maxEventsPerUser = Math.max(1, maxEventsPerUser);
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize file memory repository directory: " + baseDirectory, ex);
        }
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

        UserMemoryLog log = loadUserLog(userId);
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
            if (event.episodic() != null) {
                episodic.add(event.episodic());
            }
            if (event.semantic() != null) {
                semantic.add(event.semantic());
            }
            if (event.procedural() != null) {
                procedural.add(event.procedural());
            }
            cursor = Math.max(cursor, event.cursor());
        }

        return new MemorySyncSnapshot(cursor, episodic, semantic, procedural);
    }

    private MemoryAppendResult appendEvent(String userId,
                                           ConversationTurn episodic,
                                           SemanticMemoryEntry semantic,
                                           ProceduralMemoryEntry procedural,
                                           String eventId) {
        UserMemoryLog log = loadUserLog(userId);
        log.lock.writeLock().lock();
        try {
            if (eventId != null && !eventId.isBlank()) {
                if (!log.seenEventIds.add(eventId)) {
                    return new MemoryAppendResult(log.cursor.get(), false);
                }
                log.eventIdOrder.addLast(eventId);
            }

            long cursor = log.cursor.incrementAndGet();
            MemoryEvent event = new MemoryEvent(cursor, episodic, semantic, procedural);
            log.events.add(event);
            appendToDisk(userId, new PersistedMemoryEvent(cursor, eventId, episodic, semantic, procedural, Instant.now().toEpochMilli()));
            enforceRetention(log);
            return new MemoryAppendResult(cursor, true);
        } finally {
            log.lock.writeLock().unlock();
        }
    }

    private UserMemoryLog loadUserLog(String userId) {
        UserMemoryLog log = logsByUser.computeIfAbsent(userId, ignored -> new UserMemoryLog());
        if (log.loaded) {
            return log;
        }
        log.lock.writeLock().lock();
        try {
            if (log.loaded) {
                return log;
            }
            Path filePath = resolveUserFile(userId);
            if (Files.exists(filePath)) {
                for (String line : Files.readAllLines(filePath, StandardCharsets.UTF_8)) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    PersistedMemoryEvent persisted = objectMapper.readValue(line, PersistedMemoryEvent.class);
                    log.cursor.set(Math.max(log.cursor.get(), persisted.cursor()));
                    log.events.add(new MemoryEvent(
                            persisted.cursor(),
                            persisted.episodic(),
                            persisted.semantic(),
                            persisted.procedural()
                    ));
                    if (persisted.eventId() != null && !persisted.eventId().isBlank()) {
                        log.seenEventIds.add(persisted.eventId());
                        log.eventIdOrder.addLast(persisted.eventId());
                    }
                }
            }
            enforceRetention(log);
            log.loaded = true;
            return log;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load file-backed memory for user: " + userId, ex);
        } finally {
            log.lock.writeLock().unlock();
        }
    }

    private void appendToDisk(String userId, PersistedMemoryEvent event) {
        try {
            Path filePath = resolveUserFile(userId);
            Files.createDirectories(filePath.getParent());
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(filePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to append file-backed memory event", ex);
        }
    }

    private int firstIndexGreaterThan(List<MemoryEvent> events, long cursorExclusive) {
        int low = 0;
        int high = events.size() - 1;
        int answer = events.size();
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (events.get(mid).cursor() > cursorExclusive) {
                answer = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return answer;
    }

    private void enforceRetention(UserMemoryLog log) {
        while (log.events.size() > maxEventsPerUser) {
            log.events.remove(0);
        }
        while (log.eventIdOrder.size() > maxEventsPerUser) {
            String oldest = log.eventIdOrder.removeFirst();
            log.seenEventIds.remove(oldest);
        }
    }

    private Path resolveUserFile(String userId) {
        String normalizedUserId = userId == null ? "anonymous" : userId;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(normalizedUserId.getBytes(StandardCharsets.UTF_8));
        return baseDirectory.resolve(encoded + ".jsonl");
    }

    private static final class UserMemoryLog {
        private final AtomicLong cursor = new AtomicLong(0L);
        private final List<MemoryEvent> events = new ArrayList<>();
        private final java.util.Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
        private final ArrayDeque<String> eventIdOrder = new ArrayDeque<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private volatile boolean loaded;
    }

    private record MemoryEvent(
            long cursor,
            ConversationTurn episodic,
            SemanticMemoryEntry semantic,
            ProceduralMemoryEntry procedural
    ) {
    }

    private record PersistedMemoryEvent(
            long cursor,
            String eventId,
            ConversationTurn episodic,
            SemanticMemoryEntry semantic,
            ProceduralMemoryEntry procedural,
            long writtenAtEpochMs
    ) {
    }
}


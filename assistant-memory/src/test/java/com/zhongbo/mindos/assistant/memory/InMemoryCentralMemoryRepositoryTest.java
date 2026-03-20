package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCentralMemoryRepositoryTest {

    private static final String MAX_EVENTS_PROPERTY = "mindos.memory.inmemory.max-events-per-user";

    @AfterEach
    void cleanupProperty() {
        System.clearProperty(MAX_EVENTS_PROPERTY);
    }

    @Test
    void shouldFetchEventsIncrementallyByCursor() {
        InMemoryCentralMemoryRepository repository = new InMemoryCentralMemoryRepository();

        repository.appendEpisodic("u1", new ConversationTurn("user", "m1", Instant.now()), "e1");
        repository.appendEpisodic("u1", new ConversationTurn("assistant", "m2", Instant.now()), "e2");
        repository.appendEpisodic("u1", new ConversationTurn("user", "m3", Instant.now()), "e3");

        MemorySyncSnapshot snapshot = repository.fetchSince("u1", 1L, 10);

        assertEquals(3L, snapshot.cursor());
        assertEquals(2, snapshot.episodic().size());
        assertEquals("m2", snapshot.episodic().get(0).content());
        assertEquals("m3", snapshot.episodic().get(1).content());
    }

    @Test
    void shouldRetainOnlyLatestEventsWhenLimitExceeded() {
        System.setProperty(MAX_EVENTS_PROPERTY, "2");
        InMemoryCentralMemoryRepository repository = new InMemoryCentralMemoryRepository();

        repository.appendEpisodic("u2", new ConversationTurn("user", "first", Instant.now()), "r1");
        repository.appendEpisodic("u2", new ConversationTurn("user", "second", Instant.now()), "r2");
        repository.appendEpisodic("u2", new ConversationTurn("user", "third", Instant.now()), "r3");

        MemorySyncSnapshot snapshot = repository.fetchSince("u2", 0L, 10);

        assertEquals(3L, snapshot.cursor());
        assertEquals(2, snapshot.episodic().size());
        assertEquals("second", snapshot.episodic().get(0).content());
        assertEquals("third", snapshot.episodic().get(1).content());
    }

    @Test
    void shouldEvictOldEventIdsWithRetentionWindow() {
        System.setProperty(MAX_EVENTS_PROPERTY, "2");
        InMemoryCentralMemoryRepository repository = new InMemoryCentralMemoryRepository();

        MemoryAppendResult first = repository.appendEpisodic("u3", new ConversationTurn("user", "a", Instant.now()), "dup-1");
        MemoryAppendResult duplicate = repository.appendEpisodic("u3", new ConversationTurn("user", "b", Instant.now()), "dup-1");
        repository.appendEpisodic("u3", new ConversationTurn("user", "c", Instant.now()), "dup-2");
        repository.appendEpisodic("u3", new ConversationTurn("user", "d", Instant.now()), "dup-3");
        MemoryAppendResult acceptedAgain = repository.appendEpisodic("u3", new ConversationTurn("user", "e", Instant.now()), "dup-1");

        assertTrue(first.accepted());
        assertFalse(duplicate.accepted());
        assertTrue(acceptedAgain.accepted());
    }

    @Test
    void shouldFetchSinceWithinReasonableTimeAtMediumScale() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            InMemoryCentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
            for (int i = 0; i < 12_000; i++) {
                repository.appendEpisodic("perf", new ConversationTurn("user", "m-" + i, Instant.now()), "id-" + i);
            }

            MemorySyncSnapshot snapshot = repository.fetchSince("perf", 10_500L, 500);
            assertEquals(500, snapshot.episodic().size());
            assertEquals(11_000L, snapshot.cursor());
        });
    }
}


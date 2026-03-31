package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCentralMemoryRepositoryTest {

    @Test
    void shouldPersistAndReloadEventsAcrossRepositoryInstances() throws Exception {
        Path baseDir = Files.createTempDirectory("mindos-file-repo-");
        FileCentralMemoryRepository first = new FileCentralMemoryRepository(baseDir, new ObjectMapper());

        first.appendEpisodic("u1", ConversationTurn.user("hello"), "event-1");
        first.appendEpisodic("u1", ConversationTurn.assistant("world"), "event-2");

        FileCentralMemoryRepository second = new FileCentralMemoryRepository(baseDir, new ObjectMapper());
        MemorySyncSnapshot snapshot = second.fetchSince("u1", 0L, 10);

        assertEquals(2, snapshot.episodic().size());
        assertTrue(snapshot.episodic().get(0).content().contains("hello"));
        assertTrue(snapshot.episodic().get(1).content().contains("world"));
    }

    @Test
    void shouldDeduplicateByEventId() throws Exception {
        Path baseDir = Files.createTempDirectory("mindos-file-repo-dedup-");
        FileCentralMemoryRepository repository = new FileCentralMemoryRepository(baseDir, new ObjectMapper());

        assertTrue(repository.appendEpisodic("u2", ConversationTurn.user("hi"), "same-id").accepted());
        assertFalse(repository.appendEpisodic("u2", ConversationTurn.user("hi again"), "same-id").accepted());
        assertEquals(1, repository.fetchSince("u2", 0L, 10).episodic().size());
    }
}


package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemorySyncServiceTest {

    @Test
    void shouldNotDuplicateLocalMemoryWhenApplyingSameEventTwice() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        MemorySyncService service = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService
        );

        MemorySyncBatch batch = new MemorySyncBatch(
                "evt-repeat",
                List.of(new ConversationTurn("user", "hello", Instant.now())),
                List.of(new SemanticMemoryEntry("topic", List.of(1.0, 0.2), Instant.now())),
                List.of(new ProceduralMemoryEntry("code.generate", "build endpoint", true, Instant.now()))
        );

        MemoryApplyResult first = service.applyUpdates("sync-user", batch);
        MemoryApplyResult second = service.applyUpdates("sync-user", batch);

        assertEquals(3, first.acceptedCount());
        assertEquals(0, first.skippedCount());
        assertEquals(0, second.acceptedCount());
        assertEquals(3, second.skippedCount());

        assertEquals(1, episodicMemoryService.getConversation("sync-user").size());
        assertEquals(1, semanticMemoryService.search("sync-user", 10).size());
        assertEquals(1, proceduralMemoryService.getHistory("sync-user").size());
    }

    @Test
    void shouldNormalizeAndDeduplicateSemanticMemoryWithinBatch() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        CentralMemoryRepository repository = new InMemoryCentralMemoryRepository();
        EpisodicMemoryService episodicMemoryService = new EpisodicMemoryService();
        SemanticMemoryService semanticMemoryService = new SemanticMemoryService(consolidationService);
        ProceduralMemoryService proceduralMemoryService = new ProceduralMemoryService();
        MemorySyncService service = new MemorySyncService(
                repository,
                episodicMemoryService,
                semanticMemoryService,
                proceduralMemoryService,
                consolidationService
        );

        MemorySyncBatch batch = new MemorySyncBatch(
                "evt-normalize",
                List.of(),
                List.of(
                        new SemanticMemoryEntry("  Spring   Boot  ", List.of(1.1234567, 2.9876543, 3.1111111, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), Instant.now()),
                        new SemanticMemoryEntry("Spring Boot", List.of(), Instant.now())
                ),
                List.of()
        );

        MemoryApplyResult result = service.applyUpdates("normalize-user", batch);

        List<SemanticMemoryEntry> entries = semanticMemoryService.search("normalize-user", "spring", 10);
        assertEquals(1, result.acceptedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(1, entries.size());
        assertEquals("Spring Boot", entries.get(0).text());
        assertEquals(8, entries.get(0).embedding().size());
    }
}


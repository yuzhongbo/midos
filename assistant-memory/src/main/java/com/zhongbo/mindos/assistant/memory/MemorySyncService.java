package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Service;

@Service
public class MemorySyncService {

    private static final String PROP_WRITE_GATE_ENABLED = "mindos.memory.write-gate.enabled";
    private static final String PROP_WRITE_GATE_MIN_LENGTH = "mindos.memory.write-gate.min-length";

    private final CentralMemoryRepository centralMemoryRepository;
    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final MemoryConsolidationService memoryConsolidationService;

    public MemorySyncService(CentralMemoryRepository centralMemoryRepository,
                             EpisodicMemoryService episodicMemoryService,
                             SemanticMemoryService semanticMemoryService,
                             ProceduralMemoryService proceduralMemoryService,
                             MemoryConsolidationService memoryConsolidationService) {
        this.centralMemoryRepository = centralMemoryRepository;
        this.episodicMemoryService = episodicMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.proceduralMemoryService = proceduralMemoryService;
        this.memoryConsolidationService = memoryConsolidationService;
    }

    public MemorySyncSnapshot fetchUpdates(String userId, long sinceCursorExclusive, int limit) {
        return centralMemoryRepository.fetchSince(userId, sinceCursorExclusive, limit);
    }

    public MemoryApplyResult applyUpdates(String userId, MemorySyncBatch batch) {
        MemorySyncBatch consolidatedBatch = memoryConsolidationService.consolidateBatch(batch);
        long cursor = 0L;
        int accepted = 0;
        int skipped = 0;
        int deduplicated = 0;
        int keySignalInput = 0;
        int keySignalStored = 0;
        String eventId = consolidatedBatch.eventId();

        for (ConversationTurn turn : consolidatedBatch.episodic()) {
            MemoryAppendResult result = centralMemoryRepository.appendEpisodic(userId, turn, withSuffix(eventId, "episodic", accepted + skipped));
            cursor = Math.max(cursor, result.cursor());
            if (result.accepted()) {
                episodicMemoryService.appendTurn(userId, turn);
                accepted++;
            } else {
                skipped++;
                deduplicated++;
            }
        }

        for (SemanticMemoryEntry entry : consolidatedBatch.semantic()) {
            if (!shouldStoreSemanticMemory(entry.text())) {
                skipped++;
                continue;
            }
            boolean keySignalEntry = memoryConsolidationService.containsKeySignal(entry.text());
            if (keySignalEntry) {
                keySignalInput++;
            }
            if (semanticMemoryService.containsEquivalentEntry(userId, entry)) {
                skipped++;
                deduplicated++;
                continue;
            }
            MemoryAppendResult result = centralMemoryRepository.appendSemantic(userId, entry, withSuffix(eventId, "semantic", accepted + skipped));
            cursor = Math.max(cursor, result.cursor());
            if (result.accepted()) {
                semanticMemoryService.addEntry(userId, entry);
                accepted++;
                if (keySignalEntry) {
                    keySignalStored++;
                }
            } else {
                skipped++;
                deduplicated++;
            }
        }

        for (ProceduralMemoryEntry entry : consolidatedBatch.procedural()) {
            MemoryAppendResult result = centralMemoryRepository.appendProcedural(userId, entry, withSuffix(eventId, "procedural", accepted + skipped));
            cursor = Math.max(cursor, result.cursor());
            if (result.accepted()) {
                proceduralMemoryService.addEntry(userId, entry);
                accepted++;
            } else {
                skipped++;
                deduplicated++;
            }
        }

        return new MemoryApplyResult(cursor, accepted, skipped, deduplicated, keySignalInput, keySignalStored);
    }

    public long recordEpisodic(String userId, ConversationTurn turn) {
        ConversationTurn consolidated = memoryConsolidationService.consolidateConversationTurn(turn);
        if (consolidated == null || consolidated.content().isBlank()) {
            return 0L;
        }
        return centralMemoryRepository.appendEpisodic(userId, consolidated, null).cursor();
    }

    public long recordSemantic(String userId, SemanticMemoryEntry entry) {
        return recordSemantic(userId, entry, null);
    }

    public long recordSemantic(String userId, SemanticMemoryEntry entry, String bucket) {
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return 0L;
        }
        if (!shouldStoreSemanticMemory(consolidated.text())) {
            return 0L;
        }
        if (semanticMemoryService.containsEquivalentEntry(userId, consolidated, bucket)) {
            return 0L;
        }
        return centralMemoryRepository.appendSemantic(userId, consolidated, null).cursor();
    }

    public long recordProcedural(String userId, ProceduralMemoryEntry entry) {
        ProceduralMemoryEntry consolidated = memoryConsolidationService.consolidateProceduralEntry(entry);
        if (consolidated == null || consolidated.skillName().isBlank() || consolidated.input().isBlank()) {
            return 0L;
        }
        return centralMemoryRepository.appendProcedural(userId, consolidated, null).cursor();
    }

    private String withSuffix(String baseEventId, String stream, int index) {
        if (baseEventId == null || baseEventId.isBlank()) {
            return null;
        }
        return baseEventId + ":" + stream + ":" + index;
    }

    private boolean shouldStoreSemanticMemory(String text) {
        if (!Boolean.parseBoolean(System.getProperty(PROP_WRITE_GATE_ENABLED, "false"))) {
            return true;
        }
        String normalized = memoryConsolidationService.normalizeText(text);
        if (normalized.isBlank()) {
            return false;
        }
        int minLength = parsePositiveInt(System.getProperty(PROP_WRITE_GATE_MIN_LENGTH, "10"), 10);
        if (memoryConsolidationService.containsKeySignal(normalized)) {
            return true;
        }
        return normalized.length() >= minLength;
    }

    private int parsePositiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}


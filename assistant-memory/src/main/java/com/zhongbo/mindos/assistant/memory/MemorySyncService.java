package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemoryApplyResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class MemorySyncService {

    private static final Logger LOGGER = Logger.getLogger(MemorySyncService.class.getName());
    private final CentralMemoryRepository centralMemoryRepository;
    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final MemoryConsolidationService memoryConsolidationService;
    private final SemanticWriteGatePolicy semanticWriteGatePolicy;

    public MemorySyncService(CentralMemoryRepository centralMemoryRepository,
                             EpisodicMemoryService episodicMemoryService,
                             SemanticMemoryService semanticMemoryService,
                             ProceduralMemoryService proceduralMemoryService,
                             MemoryConsolidationService memoryConsolidationService,
                             SemanticWriteGatePolicy semanticWriteGatePolicy) {
        this.centralMemoryRepository = centralMemoryRepository;
        this.episodicMemoryService = episodicMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.proceduralMemoryService = proceduralMemoryService;
        this.memoryConsolidationService = memoryConsolidationService;
        this.semanticWriteGatePolicy = semanticWriteGatePolicy;
    }

    /**
     * Export central repository files to the given target directory.
     * Currently only supports file-backed CentralMemoryRepository implementations (FileCentralMemoryRepository).
     */
    public void exportSnapshot(Path targetDirectory) {
        try {
            if (centralMemoryRepository instanceof com.zhongbo.mindos.assistant.memory.FileCentralMemoryRepository fileRepo) {
                // reflectively access baseDirectory since it's not exposed on the interface
                try {
                    java.lang.reflect.Field baseDirField = com.zhongbo.mindos.assistant.memory.FileCentralMemoryRepository.class.getDeclaredField("baseDirectory");
                    baseDirField.setAccessible(true);
                    Path baseDir = (Path) baseDirField.get(fileRepo);
                    if (baseDir == null || !Files.exists(baseDir)) {
                        LOGGER.info("No file-backed memory directory found to export: " + baseDir);
                        return;
                    }
                    Files.createDirectories(targetDirectory);
                    try (Stream<Path> stream = Files.walk(baseDir)) {
                        stream.forEach(source -> {
                            try {
                                Path relative = baseDir.relativize(source);
                                Path dest = targetDirectory.resolve(relative.toString());
                                if (Files.isDirectory(source)) {
                                    Files.createDirectories(dest);
                                } else {
                                    Files.createDirectories(dest.getParent());
                                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                    LOGGER.info("Memory snapshot exported to: " + targetDirectory + " at " + Instant.now());
                    return;
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    LOGGER.log(Level.WARNING, "Failed to reflect FileCentralMemoryRepository.baseDirectory", ex);
                }
            }
            throw new UnsupportedOperationException("exportSnapshot is only supported for file-backed central memory repository in this release");
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to export memory snapshot", ex);
        }
    }

    /**
     * Import (restore) central repository files from the provided source directory into the active file-backed repository.
     */
    public void importSnapshot(Path sourceDirectory) {
        try {
            if (centralMemoryRepository instanceof com.zhongbo.mindos.assistant.memory.FileCentralMemoryRepository fileRepo) {
                try {
                    java.lang.reflect.Field baseDirField = com.zhongbo.mindos.assistant.memory.FileCentralMemoryRepository.class.getDeclaredField("baseDirectory");
                    baseDirField.setAccessible(true);
                    Path baseDir = (Path) baseDirField.get(fileRepo);
                    if (baseDir == null) {
                        throw new IllegalStateException("FileCentralMemoryRepository.baseDirectory is null");
                    }
                    if (!Files.exists(sourceDirectory)) {
                        LOGGER.info("Snapshot source does not exist, skipping import: " + sourceDirectory);
                        return;
                    }
                    Files.createDirectories(baseDir);
                    try (Stream<Path> stream = Files.walk(sourceDirectory)) {
                        stream.forEach(source -> {
                            try {
                                Path relative = sourceDirectory.relativize(source);
                                Path dest = baseDir.resolve(relative.toString());
                                if (Files.isDirectory(source)) {
                                    Files.createDirectories(dest);
                                } else {
                                    Files.createDirectories(dest.getParent());
                                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                    LOGGER.info("Memory snapshot imported from: " + sourceDirectory + " at " + Instant.now());
                    return;
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    LOGGER.log(Level.WARNING, "Failed to reflect FileCentralMemoryRepository.baseDirectory", ex);
                }
            }
            throw new UnsupportedOperationException("importSnapshot is only supported for file-backed central memory repository in this release");
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to import memory snapshot", ex);
        }
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
            if (!semanticWriteGatePolicy.shouldStore(entry.text(), null)) {
                skipped++;
                continue;
            }
            boolean keySignalEntry = memoryConsolidationService.containsKeySignal(entry.text());
            if (keySignalEntry) {
                keySignalInput++;
            }
            if (!semanticMemoryService.storeAcceptedEntry(userId, entry)) {
                skipped++;
                deduplicated++;
                continue;
            }
            MemoryAppendResult result = centralMemoryRepository.appendSemantic(userId, entry, withSuffix(eventId, "semantic", accepted + skipped));
            cursor = Math.max(cursor, result.cursor());
            if (result.accepted()) {
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
        return appendSemanticRecord(userId, entry, bucket, false);
    }

    public long recordAcceptedSemantic(String userId, SemanticMemoryEntry entry, String bucket) {
        return appendSemanticRecord(userId, entry, bucket, true);
    }

    private long appendSemanticRecord(String userId,
                                      SemanticMemoryEntry entry,
                                      String bucket,
                                      boolean skipLocalDuplicateCheck) {
        SemanticMemoryEntry consolidated = memoryConsolidationService.consolidateSemanticEntry(entry);
        if (consolidated == null || consolidated.text().isBlank()) {
            return 0L;
        }
        if (!semanticWriteGatePolicy.shouldStore(consolidated.text(), bucket)) {
            return 0L;
        }
        if (!skipLocalDuplicateCheck && semanticMemoryService.containsEquivalentEntry(userId, consolidated, bucket)) {
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

}


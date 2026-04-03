package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationServiceTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("mindos.memory.key-signal.constraint-terms");
        System.clearProperty("mindos.memory.key-signal.deadline-terms");
        System.clearProperty("mindos.memory.key-signal.contact-terms");
    }

    @Test
    void shouldUseCustomConstraintTermsForKeySignalDetection() {
        System.setProperty("mindos.memory.key-signal.constraint-terms", "红线,必做项");
        MemoryConsolidationService service = new MemoryConsolidationService();

        assertTrue(service.containsKeySignal("这是红线任务，今天要完成"));
        assertFalse(service.containsKeySignal("普通说明，没有关键词"));
    }

    @Test
    void shouldMatchCustomTermsCaseInsensitively() {
        System.setProperty("mindos.memory.key-signal.deadline-terms", "Urgent,ASAP");
        MemoryConsolidationService service = new MemoryConsolidationService();

        assertTrue(service.containsKeySignal("please handle this asap"));
    }

    @Test
    void shouldHandleEmptyBatchListsWithoutThrowing() {
        MemoryConsolidationService service = new MemoryConsolidationService();

        assertDoesNotThrow(() -> service.consolidateBatch(new MemorySyncBatch("evt-1", null, null, null)));
    }

    @Test
    void shouldNormalizeLargeNoisyTextAndCompactEmbedding() {
        MemoryConsolidationService service = new MemoryConsolidationService();
        StringBuilder builder = new StringBuilder(20_000);
        for (int i = 0; i < 5_000; i++) {
            builder.append(" 计划\u0000").append(i).append("\t\r\n");
        }
        List<Double> embedding = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            embedding.add((double) i);
        }

        SemanticMemoryEntry consolidated = service.consolidateSemanticEntry(
                new SemanticMemoryEntry(builder.toString(), embedding, Instant.now())
        );

        assertFalse(consolidated.text().isBlank());
        assertTrue(consolidated.text().contains("计划"));
        assertEquals(8, consolidated.embedding().size());
    }

    @Test
    void shouldTrimEmbeddingTextBySentenceAndCharBudget() {
        MemoryConsolidationService service = new MemoryConsolidationService();
        MemoryRuntimeProperties properties = new MemoryRuntimeProperties();
        properties.getEmbedding().getPreprocess().setEnabled(true);
        properties.getEmbedding().getPreprocess().setMaxSentences(2);
        properties.getEmbedding().getPreprocess().setMaxChars(32);

        String raw = "第一句包含很多信息！！！第二句也需要保留。第三句应该被截断，不再进入向量。";
        String processed = service.normalizeForEmbedding(raw, properties.getEmbedding());

        assertTrue(processed.contains("第一句"));
        assertTrue(processed.contains("第二句"));
        assertFalse(processed.contains("第三句"));
        assertTrue(processed.length() <= 32);
    }
}

package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}


package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticWriteGatePolicyTest {

    @Test
    void shouldAllowAllWhenWriteGateDisabled() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.enabled");
        try {
            System.setProperty("mindos.memory.write-gate.enabled", "false");

            SemanticWriteGatePolicy policy = new SemanticWriteGatePolicy(new MemoryConsolidationService());
            assertTrue(policy.shouldStore("ok", null));
        } finally {
            restoreProperty("mindos.memory.write-gate.enabled", oldEnabled);
        }
    }

    @Test
    void shouldSkipShortLowSignalTextWhenGateEnabled() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.enabled");
        String oldMinLength = System.getProperty("mindos.memory.write-gate.min-length");
        try {
            System.setProperty("mindos.memory.write-gate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.min-length", "12");

            SemanticWriteGatePolicy policy = new SemanticWriteGatePolicy(new MemoryConsolidationService());
            assertFalse(policy.shouldStore("todo", null));
            assertTrue(policy.shouldStore("this is a long enough memory", null));
        } finally {
            restoreProperty("mindos.memory.write-gate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.min-length", oldMinLength);
        }
    }

    @Test
    void shouldAllowKeySignalTextEvenWhenShort() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.enabled");
        String oldMinLength = System.getProperty("mindos.memory.write-gate.min-length");
        try {
            System.setProperty("mindos.memory.write-gate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.min-length", "50");

            SemanticWriteGatePolicy policy = new SemanticWriteGatePolicy(new MemoryConsolidationService());
            assertTrue(policy.shouldStore("必须在2026-04-01前完成", null));
        } finally {
            restoreProperty("mindos.memory.write-gate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.min-length", oldMinLength);
        }
    }

    @Test
    void shouldUseBucketSpecificMinLengthWhenPresent() {
        String oldEnabled = System.getProperty("mindos.memory.write-gate.enabled");
        String oldMinLength = System.getProperty("mindos.memory.write-gate.min-length");
        String oldTaskMinLength = System.getProperty("mindos.memory.write-gate.min-length.task");
        try {
            System.setProperty("mindos.memory.write-gate.enabled", "true");
            System.setProperty("mindos.memory.write-gate.min-length", "20");
            System.setProperty("mindos.memory.write-gate.min-length.task", "5");

            SemanticWriteGatePolicy policy = new SemanticWriteGatePolicy(new MemoryConsolidationService());
            assertTrue(policy.shouldStore("short", "task"));
            assertFalse(policy.shouldStore("short", "profile"));
        } finally {
            restoreProperty("mindos.memory.write-gate.enabled", oldEnabled);
            restoreProperty("mindos.memory.write-gate.min-length", oldMinLength);
            restoreProperty("mindos.memory.write-gate.min-length.task", oldTaskMinLength);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}


package com.zhongbo.mindos.assistant.dispatcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.MemoryStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesRuntimePolicyStoreTest {

    @Test
    void shouldRestorePersistedUserPolicySnapshot() {
        MapBackedMemoryStateStore stateStore = new MapBackedMemoryStateStore();
        HermesRuntimePolicyStore first = new HermesRuntimePolicyStore(stateStore);
        HermesRuntimePolicySnapshot snapshot = new HermesRuntimePolicySnapshot(
                "policy-u1-1",
                "test",
                Instant.parse("2026-01-01T00:00:00Z"),
                0.22d,
                0.31d,
                0.79d,
                0.02d,
                0.03d,
                Map.of("todo.create", 0.06d),
                Map.of("todo.create", "high-success route")
        );

        first.deploy("u1", snapshot);

        HermesRuntimePolicyStore restored = new HermesRuntimePolicyStore(stateStore);

        HermesRuntimePolicySnapshot restoredSnapshot = restored.snapshotFor("u1").orElseThrow();
        assertEquals("policy-u1-1", restoredSnapshot.snapshotId());
        assertEquals(0.22d, restoredSnapshot.memorySuccessBoostWeight());
        assertEquals(0.06d, restoredSnapshot.skillScoreAdjustments().get("todo.create"));
        assertTrue(restored.effectiveSnapshot("missing").snapshotId().startsWith("policy-")
                || "policy-default".equals(restored.effectiveSnapshot("missing").snapshotId()));
    }

    private static final class MapBackedMemoryStateStore implements MemoryStateStore {
        private final Map<String, Object> values = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T readState(String fileName, TypeReference<T> typeReference, java.util.function.Supplier<T> fallbackSupplier) {
            Object value = values.get(fileName);
            return value == null ? fallbackSupplier.get() : (T) value;
        }

        @Override
        public void writeState(String fileName, Object value) {
            values.put(fileName, value);
        }
    }
}

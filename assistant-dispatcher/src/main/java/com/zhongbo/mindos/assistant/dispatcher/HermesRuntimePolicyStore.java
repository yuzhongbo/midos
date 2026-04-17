package com.zhongbo.mindos.assistant.dispatcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.MemoryStateStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

final class HermesRuntimePolicyStore {

    private static final String STATE_FILE = "hermes-runtime-policy-store.json";

    private final AtomicReference<HermesRuntimePolicySnapshot> defaultSnapshot =
            new AtomicReference<>(HermesRuntimePolicySnapshot.defaults());
    private final ConcurrentHashMap<String, HermesRuntimePolicySnapshot> snapshotsByUser = new ConcurrentHashMap<>();
    private volatile MemoryStateStore memoryStateStore = MemoryStateStore.noOp();

    HermesRuntimePolicyStore() {
        this(MemoryStateStore.noOp());
    }

    HermesRuntimePolicyStore(MemoryStateStore memoryStateStore) {
        configurePersistence(memoryStateStore);
    }

    HermesRuntimePolicySnapshot effectiveSnapshot(String userId) {
        HermesRuntimePolicySnapshot snapshot = snapshotsByUser.get(normalize(userId));
        return snapshot == null ? defaultSnapshot.get() : snapshot;
    }

    Optional<HermesRuntimePolicySnapshot> snapshotFor(String userId) {
        HermesRuntimePolicySnapshot snapshot = snapshotsByUser.get(normalize(userId));
        return Optional.ofNullable(snapshot);
    }

    void deploy(String userId, HermesRuntimePolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank()) {
            defaultSnapshot.set(snapshot);
            persistState();
            return;
        }
        snapshotsByUser.put(normalizedUserId, snapshot);
        persistState();
    }

    void clear(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank()) {
            defaultSnapshot.set(HermesRuntimePolicySnapshot.defaults());
            persistState();
            return;
        }
        snapshotsByUser.remove(normalizedUserId);
        persistState();
    }

    Map<String, HermesRuntimePolicySnapshot> snapshots() {
        return snapshotsByUser.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(snapshotsByUser));
    }

    void configurePersistence(MemoryStateStore memoryStateStore) {
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        restoreState();
    }

    private synchronized void restoreState() {
        PersistedPolicyState persisted = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                this::snapshotState
        );
        applyState(persisted);
    }

    private synchronized void persistState() {
        memoryStateStore.writeState(STATE_FILE, snapshotState());
    }

    private PersistedPolicyState snapshotState() {
        LinkedHashMap<String, HermesRuntimePolicySnapshot> normalizedSnapshots = new LinkedHashMap<>();
        snapshotsByUser.forEach((userId, snapshot) -> {
            String normalizedUserId = normalize(userId);
            if (!normalizedUserId.isBlank() && snapshot != null) {
                normalizedSnapshots.put(normalizedUserId, snapshot);
            }
        });
        return new PersistedPolicyState(
                defaultSnapshot.get() == null ? HermesRuntimePolicySnapshot.defaults() : defaultSnapshot.get(),
                normalizedSnapshots
        );
    }

    private void applyState(PersistedPolicyState persisted) {
        PersistedPolicyState safeState = persisted == null ? snapshotState() : persisted;
        defaultSnapshot.set(safeState.defaultSnapshot() == null
                ? HermesRuntimePolicySnapshot.defaults()
                : safeState.defaultSnapshot());
        snapshotsByUser.clear();
        if (safeState.snapshotsByUser() != null) {
            safeState.snapshotsByUser().forEach((userId, snapshot) -> {
                String normalizedUserId = normalize(userId);
                if (!normalizedUserId.isBlank() && snapshot != null) {
                    snapshotsByUser.put(normalizedUserId, snapshot);
                }
            });
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record PersistedPolicyState(HermesRuntimePolicySnapshot defaultSnapshot,
                                        Map<String, HermesRuntimePolicySnapshot> snapshotsByUser) {
    }
}

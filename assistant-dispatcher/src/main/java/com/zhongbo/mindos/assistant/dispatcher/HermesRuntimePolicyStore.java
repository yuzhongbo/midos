package com.zhongbo.mindos.assistant.dispatcher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

final class HermesRuntimePolicyStore {

    private final AtomicReference<HermesRuntimePolicySnapshot> defaultSnapshot =
            new AtomicReference<>(HermesRuntimePolicySnapshot.defaults());
    private final ConcurrentHashMap<String, HermesRuntimePolicySnapshot> snapshotsByUser = new ConcurrentHashMap<>();

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
            return;
        }
        snapshotsByUser.put(normalizedUserId, snapshot);
    }

    void clear(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank()) {
            defaultSnapshot.set(HermesRuntimePolicySnapshot.defaults());
            return;
        }
        snapshotsByUser.remove(normalizedUserId);
    }

    Map<String, HermesRuntimePolicySnapshot> snapshots() {
        return snapshotsByUser.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(snapshotsByUser));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

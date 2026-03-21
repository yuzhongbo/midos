package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfileExplain;
import com.zhongbo.mindos.assistant.memory.model.PendingPreferenceOverride;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PreferenceProfileService {

    private final Map<String, PreferenceProfile> profilesByUser = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PendingOverride>> pendingOverridesByUser = new ConcurrentHashMap<>();
    private final int overwriteConfirmTurns;

    @Autowired
    public PreferenceProfileService(@Value("${mindos.memory.preference.overwrite-confirm-turns:2}") int overwriteConfirmTurns) {
        this.overwriteConfirmTurns = Math.max(1, overwriteConfirmTurns);
    }

    PreferenceProfileService(int overwriteConfirmTurns, boolean ignoredForTests) {
        this.overwriteConfirmTurns = Math.max(1, overwriteConfirmTurns);
    }

    public PreferenceProfile getProfile(String userId) {
        return profilesByUser.getOrDefault(userId, PreferenceProfile.empty());
    }

    public synchronized PreferenceProfileExplain getProfileExplain(String userId) {
        PreferenceProfile confirmed = getProfile(userId);
        Map<String, PendingOverride> pendingByField = pendingOverridesByUser.getOrDefault(userId, Map.of());
        java.util.List<PendingPreferenceOverride> pending = pendingByField.entrySet().stream()
                .map(entry -> toPendingOverride(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(PendingPreferenceOverride::field))
                .toList();
        return new PreferenceProfileExplain(confirmed, pending);
    }

    public synchronized PreferenceProfile updateProfile(String userId, PreferenceProfile incoming) {
        if (incoming == null) {
            return getProfile(userId);
        }
        PreferenceProfile base = getProfile(userId);
        PreferenceProfile merged = new PreferenceProfile(
                mergeField(userId, "assistantName", base.assistantName(), incoming.assistantName()),
                mergeField(userId, "role", base.role(), incoming.role()),
                mergeField(userId, "style", base.style(), incoming.style()),
                mergeField(userId, "language", base.language(), incoming.language()),
                mergeField(userId, "timezone", base.timezone(), incoming.timezone()),
                mergeField(userId, "preferredChannel", base.preferredChannel(), incoming.preferredChannel())
        );
        profilesByUser.put(userId, merged);
        return merged;
    }

    private String mergeField(String userId, String fieldName, String base, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return base;
        }
        if (base == null || base.isBlank() || base.equals(incoming) || overwriteConfirmTurns <= 1) {
            clearPendingOverride(userId, fieldName);
            return incoming;
        }

        Map<String, PendingOverride> pendingByField = pendingOverridesByUser.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>());
        PendingOverride pending = pendingByField.get(fieldName);
        int nextCount = (pending != null && incoming.equals(pending.value())) ? pending.count() + 1 : 1;
        if (nextCount >= overwriteConfirmTurns) {
            clearPendingOverride(userId, fieldName);
            return incoming;
        }
        pendingByField.put(fieldName, new PendingOverride(incoming, nextCount));
        return base;
    }

    private void clearPendingOverride(String userId, String fieldName) {
        Map<String, PendingOverride> pendingByField = pendingOverridesByUser.get(userId);
        if (pendingByField == null) {
            return;
        }
        pendingByField.remove(fieldName);
        if (pendingByField.isEmpty()) {
            pendingOverridesByUser.remove(userId);
        }
    }

    private PendingPreferenceOverride toPendingOverride(String field, PendingOverride pending) {
        int remaining = Math.max(0, overwriteConfirmTurns - pending.count());
        return new PendingPreferenceOverride(
                field,
                pending.value(),
                pending.count(),
                overwriteConfirmTurns,
                remaining
        );
    }

    private record PendingOverride(String value, int count) {
    }
}


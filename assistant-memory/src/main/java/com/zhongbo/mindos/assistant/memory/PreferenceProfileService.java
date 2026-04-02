package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
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

    private static final String STATE_FILE = "preference-profiles.json";
    private static final String DEFAULT_ASSISTANT_NAME = "MindOS";
    private static final String DEFAULT_ROLE = "personal-assistant";
    private static final String DEFAULT_STYLE = "warm";
    private static final String DEFAULT_LANGUAGE = "zh-CN";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final String DEFAULT_PREFERRED_CHANNEL = "";

    private final Map<String, PreferenceProfile> profilesByUser = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PendingOverride>> pendingOverridesByUser = new ConcurrentHashMap<>();
    private final int overwriteConfirmTurns;
    private final PreferenceProfile defaultProfile;
    private final MemoryStateStore memoryStateStore;

    @Autowired
    public PreferenceProfileService(
            @Value("${mindos.memory.preference.overwrite-confirm-turns:2}") int overwriteConfirmTurns,
            @Value("${mindos.memory.preference.default.assistant-name:" + DEFAULT_ASSISTANT_NAME + "}") String defaultAssistantName,
            @Value("${mindos.memory.preference.default.role:" + DEFAULT_ROLE + "}") String defaultRole,
            @Value("${mindos.memory.preference.default.style:" + DEFAULT_STYLE + "}") String defaultStyle,
            @Value("${mindos.memory.preference.default.language:" + DEFAULT_LANGUAGE + "}") String defaultLanguage,
            @Value("${mindos.memory.preference.default.timezone:" + DEFAULT_TIMEZONE + "}") String defaultTimezone,
            @Value("${mindos.memory.preference.default.preferred-channel:" + DEFAULT_PREFERRED_CHANNEL + "}") String defaultPreferredChannel,
            MemoryStateStore memoryStateStore) {
        this(overwriteConfirmTurns, new PreferenceProfile(
                normalizeDefault(defaultAssistantName, DEFAULT_ASSISTANT_NAME),
                normalizeDefault(defaultRole, DEFAULT_ROLE),
                normalizeDefault(defaultStyle, DEFAULT_STYLE),
                normalizeDefault(defaultLanguage, DEFAULT_LANGUAGE),
                normalizeDefault(defaultTimezone, DEFAULT_TIMEZONE),
                normalizeDefault(defaultPreferredChannel, DEFAULT_PREFERRED_CHANNEL)
        ), memoryStateStore);
    }

    PreferenceProfileService(int overwriteConfirmTurns, boolean ignoredForTests) {
        this(overwriteConfirmTurns);
    }

    public PreferenceProfileService(int overwriteConfirmTurns) {
        this(overwriteConfirmTurns, new PreferenceProfile(
                DEFAULT_ASSISTANT_NAME,
                DEFAULT_ROLE,
                DEFAULT_STYLE,
                DEFAULT_LANGUAGE,
                DEFAULT_TIMEZONE,
                DEFAULT_PREFERRED_CHANNEL
        ), MemoryStateStore.noOp());
    }

    PreferenceProfileService(int overwriteConfirmTurns, PreferenceProfile defaultProfile) {
        this(overwriteConfirmTurns, defaultProfile, MemoryStateStore.noOp());
    }

    PreferenceProfileService(int overwriteConfirmTurns, PreferenceProfile defaultProfile, MemoryStateStore memoryStateStore) {
        this.overwriteConfirmTurns = Math.max(1, overwriteConfirmTurns);
        this.defaultProfile = sanitizeDefaultProfile(defaultProfile);
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        loadState();
    }

    public PreferenceProfile getProfile(String userId) {
        PreferenceProfile stored = profilesByUser.getOrDefault(userId, PreferenceProfile.empty());
        return mergeWithDefaults(stored);
    }

    public synchronized PreferenceProfileExplain getProfileExplain(String userId) {
        PreferenceProfile confirmed = mergeWithDefaults(profilesByUser.getOrDefault(userId, PreferenceProfile.empty()));
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
        PreferenceProfile base = profilesByUser.getOrDefault(userId, PreferenceProfile.empty());
        PreferenceProfile merged = new PreferenceProfile(
                mergeField(userId, "assistantName", base.assistantName(), incoming.assistantName()),
                mergeField(userId, "role", base.role(), incoming.role()),
                mergeField(userId, "style", base.style(), incoming.style()),
                mergeField(userId, "language", base.language(), incoming.language()),
                mergeField(userId, "timezone", base.timezone(), incoming.timezone()),
                mergeField(userId, "preferredChannel", base.preferredChannel(), incoming.preferredChannel())
        );
        profilesByUser.put(userId, merged);
        persistState();
        return mergeWithDefaults(merged);
    }

    private void loadState() {
        PersistedPreferenceState state = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                PersistedPreferenceState::empty
        );
        state.profilesByUser().forEach((userId, profile) -> {
            if (userId != null && profile != null) {
                profilesByUser.put(userId, profile);
            }
        });
        state.pendingOverridesByUser().forEach((userId, pendingByField) -> {
            if (userId == null || pendingByField == null || pendingByField.isEmpty()) {
                return;
            }
            Map<String, PendingOverride> normalized = new ConcurrentHashMap<>();
            pendingByField.forEach((field, pending) -> {
                if (field != null && pending != null && pending.value() != null && !pending.value().isBlank()) {
                    normalized.put(field, pending);
                }
            });
            if (!normalized.isEmpty()) {
                pendingOverridesByUser.put(userId, normalized);
            }
        });
    }

    private void persistState() {
        Map<String, PreferenceProfile> profilesSnapshot = new ConcurrentHashMap<>(profilesByUser);
        Map<String, Map<String, PendingOverride>> pendingSnapshot = new ConcurrentHashMap<>();
        pendingOverridesByUser.forEach((userId, pendingByField) ->
                pendingSnapshot.put(userId, new ConcurrentHashMap<>(pendingByField)));
        memoryStateStore.writeState(STATE_FILE, new PersistedPreferenceState(profilesSnapshot, pendingSnapshot));
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

    private PreferenceProfile mergeWithDefaults(PreferenceProfile profile) {
        PreferenceProfile base = profile == null ? PreferenceProfile.empty() : profile;
        return new PreferenceProfile(
                pickNonBlank(base.assistantName(), defaultProfile.assistantName()),
                pickNonBlank(base.role(), defaultProfile.role()),
                pickNonBlank(base.style(), defaultProfile.style()),
                pickNonBlank(base.language(), defaultProfile.language()),
                pickNonBlank(base.timezone(), defaultProfile.timezone()),
                pickNonBlank(base.preferredChannel(), defaultProfile.preferredChannel())
        );
    }

    private PreferenceProfile sanitizeDefaultProfile(PreferenceProfile defaults) {
        PreferenceProfile safe = defaults == null ? PreferenceProfile.empty() : defaults;
        return new PreferenceProfile(
                pickNonBlank(safe.assistantName(), DEFAULT_ASSISTANT_NAME),
                pickNonBlank(safe.role(), DEFAULT_ROLE),
                pickNonBlank(safe.style(), DEFAULT_STYLE),
                pickNonBlank(safe.language(), DEFAULT_LANGUAGE),
                pickNonBlank(safe.timezone(), DEFAULT_TIMEZONE),
                pickNonBlank(safe.preferredChannel(), DEFAULT_PREFERRED_CHANNEL)
        );
    }

    private String pickNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static String normalizeDefault(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        return candidate.trim();
    }

    private record PendingOverride(String value, int count) {
    }

    private record PersistedPreferenceState(
            Map<String, PreferenceProfile> profilesByUser,
            Map<String, Map<String, PendingOverride>> pendingOverridesByUser
    ) {
        private static PersistedPreferenceState empty() {
            return new PersistedPreferenceState(Map.of(), Map.of());
        }
    }
}

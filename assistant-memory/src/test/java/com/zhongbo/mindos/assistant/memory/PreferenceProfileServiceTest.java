package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfileExplain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreferenceProfileServiceTest {

    @Test
    void shouldReturnDefaultProfileByDefault() {
        PreferenceProfileService service = new PreferenceProfileService(2);
        PreferenceProfile profile = service.getProfile("u1");

        assertEquals("MindOS", profile.assistantName());
        assertEquals("personal-assistant", profile.role());
        assertEquals("warm", profile.style());
        assertEquals("zh-CN", profile.language());
        assertEquals("Asia/Shanghai", profile.timezone());
    }

    @Test
    void shouldMergeIncomingProfileFields() {
        PreferenceProfileService service = new PreferenceProfileService(2, true);
        service.updateProfile("u1", new PreferenceProfile("MindOS", null, "concise", null, null, "echo"));
        PreferenceProfile merged = service.updateProfile("u1", new PreferenceProfile(null, "high-school", null, "zh-CN", null, null));

        assertEquals("MindOS", merged.assistantName());
        assertEquals("high-school", merged.role());
        assertEquals("concise", merged.style());
        assertEquals("zh-CN", merged.language());
        assertEquals("echo", merged.preferredChannel());
    }

    @Test
    void shouldRequireRepeatedConflictingValueBeforeOverwriting() {
        PreferenceProfileService service = new PreferenceProfileService(2, true);
        service.updateProfile("u1", new PreferenceProfile(null, "high-school", null, null, null, null));

        PreferenceProfile afterFirstConflict = service.updateProfile(
                "u1",
                new PreferenceProfile(null, "programmer", null, null, null, null)
        );
        PreferenceProfile afterSecondConflict = service.updateProfile(
                "u1",
                new PreferenceProfile(null, "programmer", null, null, null, null)
        );

        assertEquals("high-school", afterFirstConflict.role());
        assertEquals("programmer", afterSecondConflict.role());
    }

    @Test
    void shouldApplyConflictImmediatelyWhenThresholdIsOne() {
        PreferenceProfileService service = new PreferenceProfileService(1, true);
        service.updateProfile("u1", new PreferenceProfile(null, "high-school", null, null, null, null));

        PreferenceProfile updated = service.updateProfile(
                "u1",
                new PreferenceProfile(null, "programmer", null, null, null, null)
        );

        assertEquals("programmer", updated.role());
    }

    @Test
    void shouldExposePendingConflictInExplainSnapshot() {
        PreferenceProfileService service = new PreferenceProfileService(2, true);
        service.updateProfile("u1", new PreferenceProfile(null, "high-school", null, null, null, null));
        service.updateProfile("u1", new PreferenceProfile(null, "programmer", null, null, null, null));

        PreferenceProfileExplain explain = service.getProfileExplain("u1");

        assertEquals("high-school", explain.confirmedProfile().role());
        assertEquals(1, explain.pendingOverrides().size());
        assertEquals("role", explain.pendingOverrides().get(0).field());
        assertEquals("programmer", explain.pendingOverrides().get(0).pendingValue());
        assertEquals(1, explain.pendingOverrides().get(0).remainingConfirmTurns());
    }

    @Test
    void shouldClearPendingConflictAfterPromotion() {
        PreferenceProfileService service = new PreferenceProfileService(2, true);
        service.updateProfile("u1", new PreferenceProfile(null, "high-school", null, null, null, null));
        service.updateProfile("u1", new PreferenceProfile(null, "programmer", null, null, null, null));
        service.updateProfile("u1", new PreferenceProfile(null, "programmer", null, null, null, null));

        PreferenceProfileExplain explain = service.getProfileExplain("u1");

        assertEquals("programmer", explain.confirmedProfile().role());
        assertEquals(0, explain.pendingOverrides().size());
    }

    @Test
    void shouldPersistProfilesAndPendingOverridesAcrossRestarts(@TempDir Path tempDir) {
        MemoryStateStore stateStore = new FileMemoryStateStore(true, tempDir, new ObjectMapper());
        PreferenceProfileService first = new PreferenceProfileService(2,
                new PreferenceProfile("MindOS", "personal-assistant", "warm", "zh-CN", "Asia/Shanghai", ""),
                stateStore);
        first.updateProfile("u1", new PreferenceProfile("小助理", null, null, null, null, null));
        first.updateProfile("u1", new PreferenceProfile(null, "teacher", null, null, null, null));
        first.updateProfile("u1", new PreferenceProfile(null, "programmer", null, null, null, null));

        PreferenceProfileService second = new PreferenceProfileService(2,
                new PreferenceProfile("MindOS", "personal-assistant", "warm", "zh-CN", "Asia/Shanghai", ""),
                new FileMemoryStateStore(true, tempDir, new ObjectMapper()));

        PreferenceProfile profile = second.getProfile("u1");
        PreferenceProfileExplain explain = second.getProfileExplain("u1");
        assertEquals("小助理", profile.assistantName());
        assertEquals("teacher", profile.role());
        assertEquals(1, explain.pendingOverrides().size());
        assertEquals("programmer", explain.pendingOverrides().get(0).pendingValue());
    }
}

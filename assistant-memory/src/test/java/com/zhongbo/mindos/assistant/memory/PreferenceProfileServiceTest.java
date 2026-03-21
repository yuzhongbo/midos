package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfileExplain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreferenceProfileServiceTest {

    @Test
    void shouldReturnEmptyProfileByDefault() {
        PreferenceProfileService service = new PreferenceProfileService(2, true);
        PreferenceProfile profile = service.getProfile("u1");

        assertEquals(PreferenceProfile.empty(), profile);
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
}


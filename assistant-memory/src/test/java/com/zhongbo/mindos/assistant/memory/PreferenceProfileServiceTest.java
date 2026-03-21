package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreferenceProfileServiceTest {

    @Test
    void shouldReturnEmptyProfileByDefault() {
        PreferenceProfileService service = new PreferenceProfileService();
        PreferenceProfile profile = service.getProfile("u1");

        assertEquals(PreferenceProfile.empty(), profile);
    }

    @Test
    void shouldMergeIncomingProfileFields() {
        PreferenceProfileService service = new PreferenceProfileService();
        service.updateProfile("u1", new PreferenceProfile("MindOS", null, "concise", null, null, "echo"));
        PreferenceProfile merged = service.updateProfile("u1", new PreferenceProfile(null, "high-school", null, "zh-CN", null, null));

        assertEquals("MindOS", merged.assistantName());
        assertEquals("high-school", merged.role());
        assertEquals("concise", merged.style());
        assertEquals("zh-CN", merged.language());
        assertEquals("echo", merged.preferredChannel());
    }
}


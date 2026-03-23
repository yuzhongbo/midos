package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.dispatcher.SkillCapabilityPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillCapabilityPolicyValidationTest {

    @Test
    void shouldFailFastWhenCapabilityMapContainsUnsupportedCapability() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillCapabilityPolicy(true, "fs.read", "echo:danger.exec"));
    }

    @Test
    void shouldFailFastWhenCapabilityMapEntryIsMalformed() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillCapabilityPolicy(true, "fs.read", "echo"));
    }
}


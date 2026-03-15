package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;

public record ProceduralMemoryEntry(String skillName, String input, boolean success, Instant createdAt) {

    public static ProceduralMemoryEntry of(String skillName, String input, boolean success) {
        return new ProceduralMemoryEntry(skillName, input, success, Instant.now());
    }
}


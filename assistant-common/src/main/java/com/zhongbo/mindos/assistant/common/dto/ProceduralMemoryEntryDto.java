package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record ProceduralMemoryEntryDto(String skillName, String input, boolean success, Instant createdAt) {
}


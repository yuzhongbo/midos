package com.zhongbo.mindos.assistant.memory.model;

import java.time.Instant;
import java.util.List;

public record MemoryCompressionPlan(
        MemoryStyleProfile styleProfile,
        List<MemoryCompressionStep> steps,
        Instant createdAt
) {
}


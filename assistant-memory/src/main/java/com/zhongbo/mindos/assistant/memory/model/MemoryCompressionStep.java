package com.zhongbo.mindos.assistant.memory.model;

public record MemoryCompressionStep(
        String stage,
        String content,
        int length
) {
}


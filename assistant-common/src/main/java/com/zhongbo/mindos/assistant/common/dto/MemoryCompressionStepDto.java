package com.zhongbo.mindos.assistant.common.dto;

public record MemoryCompressionStepDto(
        String stage,
        String content,
        int length
) {
}


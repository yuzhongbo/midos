package com.zhongbo.mindos.assistant.common.dto;

public record MemoryCompressionPlanRequestDto(
        String sourceText,
        String styleName,
        String tone,
        String outputFormat
) {
}


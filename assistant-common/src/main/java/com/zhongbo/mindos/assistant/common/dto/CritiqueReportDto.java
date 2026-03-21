package com.zhongbo.mindos.assistant.common.dto;

public record CritiqueReportDto(
        boolean success,
        String reason,
        String action
) {
}


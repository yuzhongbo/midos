package com.zhongbo.mindos.assistant.common.dto;

public record SkillPreAnalyzeMetricsDto(
        String mode,
        int confidenceThreshold,
        long requests,
        long executed,
        long accepted,
        long skippedByGate,
        long skippedBySkill
) {
}


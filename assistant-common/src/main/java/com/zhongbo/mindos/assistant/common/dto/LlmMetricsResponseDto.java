package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record LlmMetricsResponseDto(
        int windowMinutes,
        long totalCalls,
        double successRate,
        double fallbackRate,
        double avgLatencyMs,
        long totalEstimatedTokens,
        List<LlmProviderAggregateDto> byProvider,
        List<LlmCallMetricDto> recentCalls,
        SecurityAuditWriteMetricsDto securityAudit,
        LlmCacheMetricsDto llmCache,
        MemoryWriteGateMetricsDto memoryWriteGate,
        ContextCompressionMetricsDto contextCompression,
        SkillPreAnalyzeMetricsDto skillPreAnalyze,
        MemoryHitMetricsDto memoryHits,
        MemoryContributionMetricsDto memoryContribution,
        LocalEscalationMetricsDto localEscalation,
        double llmCacheWindowHitRate,
        long llmCacheWindowHits,
        long llmCacheWindowMisses,
        boolean llmCacheWindowLowSample
) {
}


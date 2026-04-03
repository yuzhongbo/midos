package com.zhongbo.mindos.assistant.common.dto;

import java.util.Map;

public record LocalEscalationMetricsDto(
        long localAttempts,
        long localHits,
        double localHitRate,
        long fallbackChainAttempts,
        long fallbackChainHits,
        double fallbackChainHitRate,
        Map<String, Long> escalationReasons
) {
}


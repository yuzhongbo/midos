package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;
import java.util.List;

public record RoutingReplayItemDto(
        Instant timestamp,
        String route,
        String finalChannel,
        String ruleCandidate,
        String preAnalyzeCandidate,
        List<String> memorySegments,
        String inputSample
) {
}


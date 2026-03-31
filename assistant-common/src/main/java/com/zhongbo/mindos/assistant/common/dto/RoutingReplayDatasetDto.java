package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;
import java.util.Map;

public record RoutingReplayDatasetDto(
        int limit,
        long totalCaptured,
        List<RoutingReplayItemDto> samples,
        Map<String, Long> byRoute,
        Map<String, Long> byFinalChannel
) {
}


package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dto.LlmMetricsResponseDto;

public interface LlmMetricsReader {
    LlmMetricsResponseDto snapshot(int windowMinutes, String providerFilter, boolean includeRecent, int recentLimit);
}


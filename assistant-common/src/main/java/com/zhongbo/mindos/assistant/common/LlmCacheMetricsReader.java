package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dto.LlmCacheMetricsDto;

public interface LlmCacheMetricsReader {
    LlmCacheMetricsDto snapshotCacheMetrics();
}


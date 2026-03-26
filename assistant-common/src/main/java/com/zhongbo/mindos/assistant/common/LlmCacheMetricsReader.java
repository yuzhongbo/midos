package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dto.LlmCacheMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.LlmCacheWindowMetricsDto;

public interface LlmCacheMetricsReader {
    LlmCacheMetricsDto snapshotCacheMetrics();

    double snapshotWindowCacheHitRate(int windowMinutes);

    LlmCacheWindowMetricsDto snapshotWindowCacheMetrics(int windowMinutes);
}


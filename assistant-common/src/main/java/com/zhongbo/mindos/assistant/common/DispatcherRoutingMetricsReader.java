package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dto.MemoryHitMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.SkillPreAnalyzeMetricsDto;

public interface DispatcherRoutingMetricsReader {

    SkillPreAnalyzeMetricsDto snapshotSkillPreAnalyzeMetrics();

    MemoryHitMetricsDto snapshotMemoryHitMetrics();
}


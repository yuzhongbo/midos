package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dto.MemoryHitMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryContributionMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingReplayDatasetDto;
import com.zhongbo.mindos.assistant.common.dto.SkillPreAnalyzeMetricsDto;

public interface DispatcherRoutingMetricsReader {

    SkillPreAnalyzeMetricsDto snapshotSkillPreAnalyzeMetrics();

    MemoryHitMetricsDto snapshotMemoryHitMetrics();

    MemoryContributionMetricsDto snapshotMemoryContributionMetrics();

    RoutingReplayDatasetDto snapshotRoutingReplay(int limit);
}


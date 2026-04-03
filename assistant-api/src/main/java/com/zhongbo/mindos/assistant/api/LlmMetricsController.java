package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.ContextCompressionMetricsReader;
import com.zhongbo.mindos.assistant.common.DispatcherRoutingMetricsReader;
import com.zhongbo.mindos.assistant.common.LlmMetricsReader;
import com.zhongbo.mindos.assistant.common.LlmCacheMetricsReader;
import com.zhongbo.mindos.assistant.common.MemoryWriteGateMetricsReader;
import com.zhongbo.mindos.assistant.common.dto.LlmCacheWindowMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.LlmMetricsResponseDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingReplayDatasetDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/llm")
public class LlmMetricsController {

    private final boolean requireAdminToken;
    private final long llmCacheWindowLowSampleThreshold;
    private final AdminTokenGuard adminTokenGuard;
    private final LlmMetricsReader llmMetricsReader;
    private final LlmCacheMetricsReader llmCacheMetricsReader;
    private final MemoryWriteGateMetricsReader memoryWriteGateMetricsReader;
    private final ContextCompressionMetricsReader contextCompressionMetricsReader;
    private final DispatcherRoutingMetricsReader dispatcherRoutingMetricsReader;
    private final SecurityAuditLogService securityAuditLogService;

    public LlmMetricsController(@Value("${mindos.security.metrics.require-admin-token:true}") boolean requireAdminToken,
                                @Value("${mindos.llm.metrics.cache.window-low-sample-threshold:20}") long llmCacheWindowLowSampleThreshold,
                                AdminTokenGuard adminTokenGuard,
                                LlmMetricsReader llmMetricsReader,
                                LlmCacheMetricsReader llmCacheMetricsReader,
                                MemoryWriteGateMetricsReader memoryWriteGateMetricsReader,
                                ContextCompressionMetricsReader contextCompressionMetricsReader,
                                DispatcherRoutingMetricsReader dispatcherRoutingMetricsReader,
                                SecurityAuditLogService securityAuditLogService) {
        this.requireAdminToken = requireAdminToken;
        this.llmCacheWindowLowSampleThreshold = Math.max(1L, llmCacheWindowLowSampleThreshold);
        this.adminTokenGuard = adminTokenGuard;
        this.llmMetricsReader = llmMetricsReader;
        this.llmCacheMetricsReader = llmCacheMetricsReader;
        this.memoryWriteGateMetricsReader = memoryWriteGateMetricsReader;
        this.contextCompressionMetricsReader = contextCompressionMetricsReader;
        this.dispatcherRoutingMetricsReader = dispatcherRoutingMetricsReader;
        this.securityAuditLogService = securityAuditLogService;
    }

    @GetMapping
    public LlmMetricsResponseDto getLlmMetrics(@RequestParam(defaultValue = "60") int windowMinutes,
                                               @RequestParam(required = false) String provider,
                                               @RequestParam(defaultValue = "false") boolean includeRecent,
                                               @RequestParam(defaultValue = "20") int recentLimit,
                                               HttpServletRequest request) {
        if (requireAdminToken) {
            adminTokenGuard.verify(request, "metrics-reader", "security.metrics.llm.read", "llm-metrics");
        }
        LlmMetricsResponseDto snapshot = llmMetricsReader.snapshot(windowMinutes, provider, includeRecent, recentLimit);
        LlmCacheWindowMetricsDto windowCache = llmCacheMetricsReader.snapshotWindowCacheMetrics(windowMinutes);
        boolean llmCacheWindowLowSample = (windowCache.hits() + windowCache.misses()) < llmCacheWindowLowSampleThreshold;
        return new LlmMetricsResponseDto(
                snapshot.windowMinutes(),
                snapshot.totalCalls(),
                snapshot.successRate(),
                snapshot.fallbackRate(),
                snapshot.avgLatencyMs(),
                snapshot.totalEstimatedTokens(),
                snapshot.byProvider(),
                snapshot.recentCalls(),
                securityAuditLogService.getWriteMetrics(),
                llmCacheMetricsReader.snapshotCacheMetrics(),
                memoryWriteGateMetricsReader.snapshotWriteGateMetrics(),
                contextCompressionMetricsReader.snapshotContextCompressionMetrics(),
                dispatcherRoutingMetricsReader.snapshotSkillPreAnalyzeMetrics(),
                dispatcherRoutingMetricsReader.snapshotMemoryHitMetrics(),
                dispatcherRoutingMetricsReader.snapshotMemoryContributionMetrics(),
                dispatcherRoutingMetricsReader.snapshotLocalEscalationMetrics(),
                windowCache.hitRate(),
                windowCache.hits(),
                windowCache.misses(),
                llmCacheWindowLowSample
        );
    }

    @GetMapping("/routing-replay")
    public RoutingReplayDatasetDto getRoutingReplay(@RequestParam(defaultValue = "200") int limit,
                                                    HttpServletRequest request) {
        if (requireAdminToken) {
            adminTokenGuard.verify(request, "metrics-reader", "security.metrics.llm.read", "llm-metrics");
        }
        return dispatcherRoutingMetricsReader.snapshotRoutingReplay(limit);
    }
}


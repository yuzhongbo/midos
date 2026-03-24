package com.zhongbo.mindos.assistant.llm;

import com.zhongbo.mindos.assistant.common.LlmMetricsReader;
import com.zhongbo.mindos.assistant.common.dto.LlmCallMetricDto;
import com.zhongbo.mindos.assistant.common.dto.LlmMetricsResponseDto;
import com.zhongbo.mindos.assistant.common.dto.LlmProviderAggregateDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class LlmMetricsService implements LlmMetricsReader {

    private final boolean enabled;
    private final int maxRecentCalls;
    private final ConcurrentLinkedDeque<LlmCallMetricDto> calls = new ConcurrentLinkedDeque<>();

    public LlmMetricsService(@Value("${mindos.llm.metrics.enabled:true}") boolean enabled,
                             @Value("${mindos.llm.metrics.max-recent-calls:500}") int maxRecentCalls) {
        this.enabled = enabled;
        this.maxRecentCalls = Math.max(50, maxRecentCalls);
    }

    public void record(LlmCallMetricDto call) {
        if (!enabled || call == null) {
            return;
        }
        calls.addLast(call);
        while (calls.size() > maxRecentCalls) {
            calls.pollFirst();
        }
    }

    @Override
    public LlmMetricsResponseDto snapshot(int windowMinutes,
                                          String providerFilter,
                                          boolean includeRecent,
                                          int recentLimit) {
        int normalizedWindowMinutes = Math.max(1, windowMinutes);
        int normalizedRecentLimit = Math.max(1, recentLimit);
        String normalizedProvider = normalizeProvider(providerFilter);

        Instant cutoff = Instant.now().minusSeconds((long) normalizedWindowMinutes * 60L);
        List<LlmCallMetricDto> filtered = calls.stream()
                .filter(call -> !call.timestamp().isBefore(cutoff))
                .filter(call -> normalizedProvider == null || normalizedProvider.equals(normalizeProvider(call.provider())))
                .toList();

        long totalCalls = filtered.size();
        long successCount = filtered.stream().filter(LlmCallMetricDto::success).count();
        long fallbackCount = filtered.stream().filter(this::isFallback).count();
        long totalLatency = filtered.stream().mapToLong(LlmCallMetricDto::latencyMs).sum();
        long totalTokens = filtered.stream().mapToLong(LlmCallMetricDto::totalTokensEstimate).sum();

        List<LlmProviderAggregateDto> byProvider = buildProviderAggregates(filtered);
        List<LlmCallMetricDto> recentCalls = includeRecent
                ? filtered.stream()
                .sorted(Comparator.comparing(LlmCallMetricDto::timestamp).reversed())
                .limit(normalizedRecentLimit)
                .toList()
                : List.of();

        return new LlmMetricsResponseDto(
                normalizedWindowMinutes,
                totalCalls,
                ratio(successCount, totalCalls),
                ratio(fallbackCount, totalCalls),
                totalCalls == 0 ? 0.0 : (double) totalLatency / totalCalls,
                totalTokens,
                byProvider,
                recentCalls,
                null
        );
    }

    private List<LlmProviderAggregateDto> buildProviderAggregates(List<LlmCallMetricDto> callsInWindow) {
        Map<String, List<LlmCallMetricDto>> grouped = new LinkedHashMap<>();
        for (LlmCallMetricDto call : callsInWindow) {
            grouped.computeIfAbsent(normalizeProvider(call.provider()), ignored -> new ArrayList<>()).add(call);
        }

        return grouped.entrySet().stream()
                .map(entry -> toProviderAggregate(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(LlmProviderAggregateDto::calls).reversed())
                .toList();
    }

    private LlmProviderAggregateDto toProviderAggregate(String provider, List<LlmCallMetricDto> callsByProvider) {
        long calls = callsByProvider.size();
        long successCount = callsByProvider.stream().filter(LlmCallMetricDto::success).count();
        long retryCount = callsByProvider.stream().filter(LlmCallMetricDto::retried).count();
        long fallbackCount = callsByProvider.stream().filter(this::isFallback).count();
        long totalLatency = callsByProvider.stream().mapToLong(LlmCallMetricDto::latencyMs).sum();
        long totalTokens = callsByProvider.stream().mapToLong(LlmCallMetricDto::totalTokensEstimate).sum();
        long failureCount = calls - successCount;

        return new LlmProviderAggregateDto(
                provider,
                calls,
                successCount,
                failureCount,
                retryCount,
                fallbackCount,
                ratio(successCount, calls),
                ratio(fallbackCount, calls),
                calls == 0 ? 0.0 : (double) totalLatency / calls,
                totalTokens
        );
    }

    private boolean isFallback(LlmCallMetricDto call) {
        String stage = call.routeStage();
        return stage != null && stage.toLowerCase(Locale.ROOT).contains("fallback");
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator * 1.0 / denominator;
    }
}


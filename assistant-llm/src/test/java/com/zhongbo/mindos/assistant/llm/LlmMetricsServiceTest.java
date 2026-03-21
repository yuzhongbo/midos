package com.zhongbo.mindos.assistant.llm;

import com.zhongbo.mindos.assistant.common.dto.LlmCallMetricDto;
import com.zhongbo.mindos.assistant.common.dto.LlmMetricsResponseDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMetricsServiceTest {

    @Test
    void shouldAggregateCallsByProviderAndFallbackRate() {
        LlmMetricsService service = new LlmMetricsService(true, 200);
        Instant now = Instant.now();

        service.record(new LlmCallMetricDto(now.minusSeconds(10), "u1", "openai", "https://x", "llm-fallback",
                true, false, 120, 10, 15, 25, null));
        service.record(new LlmCallMetricDto(now.minusSeconds(5), "u1", "openai", "https://x", "llm-dsl",
                false, true, 200, 12, 8, 20, "missing_api_key"));
        service.record(new LlmCallMetricDto(now.minusSeconds(4), "u2", "local", "http://y", "llm-fallback",
                true, false, 80, 5, 7, 12, null));

        LlmMetricsResponseDto snapshot = service.snapshot(60, null, true, 10);

        assertEquals(3, snapshot.totalCalls());
        assertTrue(snapshot.successRate() > 0.6 && snapshot.successRate() < 0.7);
        assertTrue(snapshot.fallbackRate() > 0.6 && snapshot.fallbackRate() < 0.7);
        assertEquals(2, snapshot.byProvider().size());
        assertEquals(3, snapshot.recentCalls().size());
    }

    @Test
    void shouldFilterByProviderAndRecentLimit() {
        LlmMetricsService service = new LlmMetricsService(true, 200);
        Instant now = Instant.now();

        service.record(new LlmCallMetricDto(now.minusSeconds(9), "u1", "openai", "https://x", "llm-fallback",
                true, false, 100, 4, 6, 10, null));
        service.record(new LlmCallMetricDto(now.minusSeconds(8), "u1", "local", "http://y", "llm-fallback",
                true, false, 100, 4, 6, 10, null));
        service.record(new LlmCallMetricDto(now.minusSeconds(7), "u1", "openai", "https://x", "llm-dsl",
                true, false, 100, 4, 6, 10, null));

        LlmMetricsResponseDto filtered = service.snapshot(60, "openai", true, 1);

        assertEquals(2, filtered.totalCalls());
        assertEquals(1, filtered.byProvider().size());
        assertEquals("openai", filtered.byProvider().get(0).provider());
        assertEquals(1, filtered.recentCalls().size());
    }

    @Test
    void shouldReturnEmptyWhenDisabled() {
        LlmMetricsService service = new LlmMetricsService(false, 200);
        service.record(new LlmCallMetricDto(Instant.now(), "u1", "openai", "https://x", "llm-fallback",
                true, false, 100, 4, 6, 10, null));

        LlmMetricsResponseDto snapshot = service.snapshot(60, null, true, 10);

        assertEquals(0, snapshot.totalCalls());
        assertFalse(snapshot.successRate() > 0);
    }
}


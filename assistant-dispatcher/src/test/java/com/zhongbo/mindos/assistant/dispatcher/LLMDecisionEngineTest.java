package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMDecisionEngineTest {

    @Test
    void shouldSkipLlmWhenRelevantMemoryExistsAndQueryIsSimple() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        boolean shouldCall = engine.shouldCallLLM(new QueryContext(
                "u1",
                "周报什么时候提交",
                new PromptMemoryContextDto(
                        "",
                        "周五前提交周报",
                        "",
                        Map.of(),
                        List.of(new RetrievedMemoryItemDto("semantic", "周五前提交周报", 0.9, 0.8, 0.9, 0.9, 1L))
                ),
                false,
                false
        ));

        assertFalse(shouldCall);
        assertTrue(engine.usageRate() < 0.20d);
    }

    @Test
    void shouldCallLlmForExplicitOrComplexQueries() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        assertTrue(engine.shouldCallLLM(new QueryContext("u1", "请详细分析这个架构 tradeoff", null, true, true)));
        assertTrue(engine.shouldCallLLM(new QueryContext("u1", "为什么这个方案更好", null, false, true)));
        assertTrue(engine.shouldCallLLM(new QueryContext("u1", "随便问问", null, false, false)));
    }

    @Test
    void shouldCallLlmForRealtimeLookupEvenWithMemoryHit() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        boolean shouldCall = engine.shouldCallLLM(new QueryContext(
                "u-weather",
                "帮我查询川西清明节期间的天气",
                new PromptMemoryContextDto(
                        "",
                        "川西旅游攻略",
                        "",
                        Map.of(),
                        List.of(new RetrievedMemoryItemDto("semantic", "去年川西行程总结", 0.92, 0.10, 0.9, 0.92, 1L))
                ),
                false,
                false
        ));

        assertTrue(shouldCall, "Realtime/weather queries should not rely on stale memory");
    }

    @Test
    void shouldCallLlmForCurrentWeatherRequestEvenWithMemoryHit() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        boolean shouldCall = engine.shouldCallLLM(new QueryContext(
                "u-weather-now",
                "成都天气现在",
                new PromptMemoryContextDto(
                        "",
                        "昨天成都天气：晴，适合外出",
                        "",
                        Map.of(),
                        List.of(new RetrievedMemoryItemDto("semantic", "昨天成都天气：晴，适合外出", 0.92, 0.10, 0.9, 0.92, 1L))
                ),
                false,
                false
        ));

        assertTrue(shouldCall, "Current weather queries should be treated as realtime and not answered from stale memory");
    }

    @Test
    void shouldCallLlmForBareWeatherQueryEvenWithoutLookupVerb() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        boolean shouldCall = engine.shouldCallLLM(new QueryContext(
                "u-weather-bare",
                "成都天气",
                new PromptMemoryContextDto(
                        "",
                        "昨天成都天气：晴，适合外出",
                        "",
                        Map.of(),
                        List.of(new RetrievedMemoryItemDto("semantic", "昨天成都天气：晴，适合外出", 0.92, 0.10, 0.9, 0.92, 1L))
                ),
                false,
                false
        ));

        assertTrue(shouldCall, "Bare weather queries should still be treated as realtime lookups");
    }

    @Test
    void shouldNotTreatHistoricalWeatherAsRealtimeQuery() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        boolean shouldCall = engine.shouldCallLLM(new QueryContext(
                "u-weather-history",
                "昨天成都天气",
                new PromptMemoryContextDto(
                        "",
                        "昨天成都天气：晴，适合外出",
                        "",
                        Map.of(),
                        List.of(new RetrievedMemoryItemDto("semantic", "昨天成都天气：晴，适合外出", 0.92, 0.10, 0.9, 0.92, 1L))
                ),
                false,
                false
        ));

        assertFalse(shouldCall, "Historical weather queries should not be forced into realtime mode");
    }

    @Test
    void shouldCallLlmForRecentNewsRequestWithoutExplicitLookupVerb() {
        LLMDecisionEngine engine = new LLMDecisionEngine();

        boolean shouldCall = engine.shouldCallLLM(new QueryContext(
                "u-news",
                "最近的国际新闻",
                new PromptMemoryContextDto(
                        "",
                        "semantic-summary intent=获取新闻, skill=news_search, summary=用户请求获取最近的国际新闻。",
                        "",
                        Map.of(),
                        List.of(new RetrievedMemoryItemDto("semantic", "semantic-summary intent=获取新闻, skill=news_search, summary=用户请求获取最近的国际新闻。", 0.95, 0.9, 0.9, 0.95, 1L))
                ),
                false,
                false
        ));

        assertTrue(shouldCall, "Recent news requests should not degrade to memory.direct just because a semantic summary exists");
    }
}

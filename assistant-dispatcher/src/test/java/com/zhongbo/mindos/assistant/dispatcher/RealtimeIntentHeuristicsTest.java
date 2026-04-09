package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RealtimeIntentHeuristicsTest {

    @Test
    void shouldClassifyBareWeatherAsRealtimeByDomainOnly() {
        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "成都天气",
                List.of("天气", "新闻")
        );

        assertTrue(signal.realtime());
        assertEquals("domain-weather", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.DOMAIN_WEATHER, signal.kind());
    }

    @Test
    void shouldClassifyFreshWeatherAsRealtimeByDomainFreshness() {
        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "成都天气现在",
                List.of("天气", "新闻")
        );

        assertTrue(signal.realtime());
        assertEquals("domain-weather", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.DOMAIN_WEATHER, signal.kind());
    }

    @Test
    void shouldClassifyLookupWeatherAsRealtimeByDomainLookup() {
        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "帮我查一下成都今天到明天天气",
                List.of("天气", "新闻")
        );

        assertTrue(signal.realtime());
        assertEquals("domain-weather", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.DOMAIN_WEATHER, signal.kind());
    }

    @Test
    void shouldRejectHistoricalWeatherQueries() {
        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "昨天成都天气",
                List.of("天气", "新闻")
        );

        assertFalse(signal.realtime());
        assertEquals("historical-query", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.HISTORICAL, signal.kind());
    }

    @Test
    void shouldPreferSemanticRealtimeSignalOverPlainTextKeywords() {
        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "llm",
                "查询天气信息",
                "帮我看下",
                "mcp.bravesearch.webSearch",
                Map.of("query", "成都明天怎么样"),
                List.of("天气", "成都"),
                "用户要查询最新天气信息",
                0.91,
                List.of(new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.95))
        );

        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "帮我看下",
                List.of("天气", "新闻"),
                semanticAnalysis
        );

        assertTrue(signal.realtime());
        assertEquals("semantic-domain-weather", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.SEMANTIC_WEATHER, signal.kind());
    }

    @Test
    void shouldClassifySemanticNewsQueryAsRealtimeNewsDomain() {
        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "llm",
                "获取新闻",
                "帮我看下",
                "news_search",
                Map.of("query", "最近的国际新闻"),
                List.of("新闻", "国际新闻"),
                "用户请求获取最新新闻",
                0.93,
                List.of(new SemanticAnalysisResult.CandidateIntent("news_search", 0.96))
        );

        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "帮我看下",
                List.of("天气", "新闻"),
                semanticAnalysis
        );

        assertTrue(signal.realtime());
        assertEquals("semantic-domain-news", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.SEMANTIC_NEWS, signal.kind());
    }

    @Test
    void shouldClassifySemanticMarketQueryAsRealtimeMarketDomain() {
        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "llm",
                "查询行情",
                "帮我看下",
                "mcp.bravesearch.webSearch",
                Map.of("query", "A股最新行情"),
                List.of("行情", "股票"),
                "用户请求查询最新行情",
                0.91,
                List.of(new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.95))
        );

        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "帮我看下",
                List.of("天气", "新闻"),
                semanticAnalysis
        );

        assertTrue(signal.realtime());
        assertEquals("semantic-domain-market", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.SEMANTIC_MARKET, signal.kind());
    }

    @Test
    void shouldClassifySemanticTravelQueryAsRealtimeTravelDomain() {
        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "llm",
                "查询出行",
                "帮我看下",
                "mcp.bravesearch.webSearch",
                Map.of("query", "上海到北京的高铁情况"),
                List.of("出行", "高铁"),
                "用户请求查询实时出行信息",
                0.9,
                List.of(new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.94))
        );

        RealtimeIntentHeuristics.RealtimeIntentSignal signal = RealtimeIntentHeuristics.analyze(
                "帮我看下",
                List.of("天气", "新闻"),
                semanticAnalysis
        );

        assertTrue(signal.realtime());
        assertEquals("semantic-domain-travel", signal.reason());
        assertEquals(RealtimeIntentHeuristics.MatchKind.SEMANTIC_TRAVEL, signal.kind());
    }
}


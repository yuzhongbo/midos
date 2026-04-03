package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IntentModelRoutingPolicyTest {

    @Test
    void shouldRouteRealtimeToGrokWithConfiguredModelTier() {
        IntentModelRoutingPolicy policy = new IntentModelRoutingPolicy(
                true,
                "gpt",
                "gpt",
                "grok",
                "gemini",
                "qwen",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2",
                "x-ai/grok-4-fast",
                "x-ai/grok-4",
                "x-ai/grok-4",
                "google/gemini-2.5-flash",
                "google/gemini-2.5-pro",
                "google/gemini-2.5-pro",
                "情绪,焦虑",
                180
        );

        Map<String, Object> llmContext = new LinkedHashMap<>();
        policy.applyForFallback(
                "今天的新闻头条是什么",
                new PromptMemoryContextDto("", "", "", Map.of(), java.util.List.of()),
                true,
                Map.of(),
                llmContext
        );

        assertEquals("grok", llmContext.get("llmProvider"));
        assertEquals("x-ai/grok-4-fast", llmContext.get("model"));
    }

    @Test
    void shouldUseEmotionalProviderForEmotionalInput() {
        IntentModelRoutingPolicy policy = new IntentModelRoutingPolicy(
                true,
                "gpt",
                "gpt",
                "grok",
                "gemini",
                "qwen",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2",
                "x-ai/grok-4-fast",
                "x-ai/grok-4",
                "x-ai/grok-4",
                "google/gemini-2.5-flash",
                "google/gemini-2.5-pro",
                "google/gemini-2.5-pro",
                "情绪,焦虑",
                180
        );

        Map<String, Object> llmContext = new LinkedHashMap<>();
        policy.applyForFallback(
                "我最近很焦虑，帮我梳理情绪",
                new PromptMemoryContextDto("", "", "", Map.of(), java.util.List.of()),
                false,
                Map.of(),
                llmContext
        );

        assertEquals("gemini", llmContext.get("llmProvider"));
        assertEquals("google/gemini-2.5-flash", llmContext.get("model"));
    }

    @Test
    void shouldKeepProfileProviderOverrideUntouched() {
        IntentModelRoutingPolicy policy = new IntentModelRoutingPolicy(
                true,
                "gpt",
                "gpt",
                "grok",
                "gemini",
                "qwen",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2",
                "openai/gpt-5-mini",
                "openai/gpt-5.2",
                "openai/gpt-5.2",
                "x-ai/grok-4-fast",
                "x-ai/grok-4",
                "x-ai/grok-4",
                "google/gemini-2.5-flash",
                "google/gemini-2.5-pro",
                "google/gemini-2.5-pro",
                "情绪,焦虑",
                180
        );

        Map<String, Object> llmContext = new LinkedHashMap<>();
        policy.applyForFallback(
                "帮我写一个java接口",
                new PromptMemoryContextDto("", "", "", Map.of(), java.util.List.of()),
                false,
                Map.of("llmProvider", "openrouter"),
                llmContext
        );

        assertNull(llmContext.get("llmProvider"));
        assertNull(llmContext.get("model"));
    }

    @Test
    void shouldRouteHardGeneralInputToHardProvider() {
        IntentModelRoutingPolicy policy = new IntentModelRoutingPolicy(
                true,
                "local",
                "local",
                "qwen",
                "qwen",
                "qwen",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "情绪,焦虑",
                120
        );

        Map<String, Object> llmContext = new LinkedHashMap<>();
        policy.applyForFallback(
                "请给我一个分阶段的系统架构设计方案，分析 tradeoff 并比较实现路径",
                new PromptMemoryContextDto("", "", "", Map.of(), java.util.List.of()),
                false,
                Map.of(),
                llmContext
        );

        assertEquals("qwen", llmContext.get("llmProvider"));
    }
}

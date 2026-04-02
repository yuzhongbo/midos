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
}

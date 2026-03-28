package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImReplySanitizerTest {

    @Test
    void shouldClassifyBlankReply() {
        ImReplySanitizer.Decision decision = ImReplySanitizer.inspect("   ");

        assertTrue(decision.sanitized());
        assertEquals("blank", decision.fallbackKind());
        assertEquals(ImReplySanitizer.BLANK_IM_FALLBACK_REPLY, decision.sanitizedReply());
        assertEquals(java.util.List.of("blank_reply"), decision.reasons());
    }

    @Test
    void shouldClassifySkeletonAndPromptLeakReply() {
        ImReplySanitizer.Decision decision = ImReplySanitizer.inspect(
                "[LLM gemini] skeleton response for user im:dingtalk:u4: Answer naturally using the context when helpful.\n"
                        + "Recent conversation:\n"
                        + "Relevant knowledge:\n"
                        + "User skill habits:\n"
                        + "User input: 优化记忆"
        );

        assertTrue(decision.sanitized());
        assertEquals("friendly", decision.fallbackKind());
        assertEquals(ImReplySanitizer.FRIENDLY_IM_FALLBACK_REPLY, decision.sanitizedReply());
        assertTrue(decision.reasons().contains("llm_marker"));
        assertTrue(decision.reasons().contains("skeleton_mode"));
        assertTrue(decision.reasons().contains("prompt_template_leak"));
        assertTrue(decision.reasons().contains("recent_conversation_leak"));
        assertTrue(decision.reasons().contains("knowledge_context_leak"));
        assertTrue(decision.reasons().contains("skill_habits_leak"));
        assertTrue(decision.reasons().contains("user_input_echo_leak"));
    }

    @Test
    void shouldKeepNormalReplyUntouched() {
        ImReplySanitizer.Decision decision = ImReplySanitizer.inspect("好的，我已经帮你整理完成。");

        assertFalse(decision.sanitized());
        assertEquals("none", decision.fallbackKind());
        assertEquals("好的，我已经帮你整理完成。", decision.sanitizedReply());
        assertTrue(decision.reasons().isEmpty());
    }
}


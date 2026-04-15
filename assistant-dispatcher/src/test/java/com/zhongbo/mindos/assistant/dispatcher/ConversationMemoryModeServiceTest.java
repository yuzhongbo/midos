package com.zhongbo.mindos.assistant.dispatcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryModeServiceTest {

    private final ConversationMemoryModeService service = new ConversationMemoryModeService();

    @Test
    void shouldDetectExplicitMemoryRecallFromColloquialPhrases() {
        assertTrue(service.isExplicitMemoryRecallRequest("刚才说啥"));
        assertTrue(service.isExplicitMemoryRecallRequest("我们刚才聊了什么"));
        assertTrue(service.isExplicitMemoryRecallRequest("你刚刚说的是什么"));
    }

    @Test
    void shouldNotTreatGenericShortConfusionAsMemoryRecall() {
        assertFalse(service.isExplicitMemoryRecallRequest("啥呀"));
        assertFalse(service.isExplicitMemoryRecallRequest("可以可以"));
    }
}

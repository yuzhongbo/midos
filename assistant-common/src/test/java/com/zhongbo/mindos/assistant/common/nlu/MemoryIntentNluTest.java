package com.zhongbo.mindos.assistant.common.nlu;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class MemoryIntentNluTest {

    @Test
    public void shouldDetectStyleShowIntent() {
        assertTrue(MemoryIntentNlu.isStyleShowIntent("查看我的记忆风格"));
    }

    @Test
    public void shouldExtractAutoTuneSample() {
        assertEquals(MemoryIntentNlu.extractAutoTuneSample("根据这段话微调记忆风格：请帮我按步骤拆分任务清单"),
                "请帮我按步骤拆分任务清单");
        assertNull(MemoryIntentNlu.extractAutoTuneSample("查看我的记忆风格"));
    }

    @Test
    public void shouldExtractCompressionIntentWithExplicitFocus() {
        MemoryIntentNlu.CompressionIntent intent = MemoryIntentNlu.extractCompressionIntent(
                "按我的风格压缩这段记忆：先拆任务再执行，按任务聚焦"
        );
        assertNotNull(intent);
        assertEquals(intent.source(), "先拆任务再执行");
        assertEquals(intent.focus(), "task");
    }

    @Test
    public void shouldNotInferFocusFromPlainKeywordsInSource() {
        MemoryIntentNlu.CompressionIntent intent = MemoryIntentNlu.extractCompressionIntent(
                "按我的风格压缩这段记忆：先拆任务再执行"
        );
        assertNotNull(intent);
        assertEquals(intent.source(), "先拆任务再执行");
        assertNull(intent.focus());
    }

    @Test
    public void shouldExtractStyleUpdateIntent() {
        MemoryIntentNlu.StyleUpdateIntent intent = MemoryIntentNlu.extractStyleUpdateIntent(
                "把记忆风格改成 action，语气 warm，格式 bullet"
        );
        assertNotNull(intent);
        assertEquals(intent.styleName(), "action");
        assertEquals(intent.tone(), "warm");
        assertEquals(intent.outputFormat(), "bullet");
        assertTrue(intent.hasValues());
    }
}


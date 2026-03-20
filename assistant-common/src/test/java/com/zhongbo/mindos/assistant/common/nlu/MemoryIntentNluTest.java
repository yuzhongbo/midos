package com.zhongbo.mindos.assistant.common.nlu;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class MemoryIntentNluTest {

    private static final String PROP_FOCUS_REVIEW_TERMS = "mindos.memory.nlu.focus.review-terms";
    private static final String PROP_STYLE_ACTION_TERMS = "mindos.memory.nlu.style.action-terms";
    private static final String PROP_TONE_WARM_TERMS = "mindos.memory.nlu.tone.warm-terms";
    private static final String PROP_FORMAT_BULLET_TERMS = "mindos.memory.nlu.format.bullet-terms";

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
    public void shouldDetectAffirmativeIntent() {
        assertTrue(MemoryIntentNlu.isAffirmativeIntent("要"));
        assertTrue(MemoryIntentNlu.isAffirmativeIntent("好的"));
        assertTrue(MemoryIntentNlu.isAffirmativeIntent("ok"));
        assertFalse(MemoryIntentNlu.isAffirmativeIntent("不用"));
        assertFalse(MemoryIntentNlu.isAffirmativeIntent("先不了"));
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
    public void shouldOnlyDetectFocusWhenExplicitSuffixExists() {
        MemoryIntentNlu.CompressionIntent intent = MemoryIntentNlu.extractCompressionIntent(
                "按我的风格压缩这段记忆：这里记录一种任务聚焦能力训练法"
        );
        assertNotNull(intent);
        assertEquals(intent.source(), "这里记录一种任务聚焦能力训练法");
        assertNull(intent.focus());
    }

    @Test
    public void shouldSupportAltTrailingFocusExpression() {
        MemoryIntentNlu.CompressionIntent intent = MemoryIntentNlu.extractCompressionIntent(
                "按我的风格压缩这段记忆：先回顾错题再总结，聚焦到复盘"
        );
        assertNotNull(intent);
        assertEquals(intent.source(), "先回顾错题再总结");
        assertEquals(intent.focus(), "review");
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

    @Test
    public void shouldNormalizeBuiltInSynonyms() {
        MemoryIntentNlu.StyleUpdateIntent styleIntent = MemoryIntentNlu.extractStyleUpdateIntent(
                "把记忆风格改成 教学，语气 友好，格式 清单"
        );
        assertNotNull(styleIntent);
        assertEquals(styleIntent.styleName(), "coach");
        assertEquals(styleIntent.tone(), "warm");
        assertEquals(styleIntent.outputFormat(), "bullet");

        MemoryIntentNlu.CompressionIntent compressionIntent = MemoryIntentNlu.extractCompressionIntent(
                "按我的风格压缩这段记忆：先回顾错题再总结，聚焦到总结"
        );
        assertNotNull(compressionIntent);
        assertEquals(compressionIntent.source(), "先回顾错题再总结");
        assertEquals(compressionIntent.focus(), "review");
    }

    @Test
    public void shouldApplyConfiguredSynonymsFromSystemProperties() {
        String oldFocus = System.getProperty(PROP_FOCUS_REVIEW_TERMS);
        String oldStyle = System.getProperty(PROP_STYLE_ACTION_TERMS);
        String oldTone = System.getProperty(PROP_TONE_WARM_TERMS);
        String oldFormat = System.getProperty(PROP_FORMAT_BULLET_TERMS);
        try {
            System.setProperty(PROP_FOCUS_REVIEW_TERMS, "复盘,retrospective");
            System.setProperty(PROP_STYLE_ACTION_TERMS, "action,行动派");
            System.setProperty(PROP_TONE_WARM_TERMS, "warm,gentle");
            System.setProperty(PROP_FORMAT_BULLET_TERMS, "bullet,markdown list");

            MemoryIntentNlu.StyleUpdateIntent styleIntent = MemoryIntentNlu.extractStyleUpdateIntent(
                    "把记忆风格改成 行动派，语气 gentle，格式 markdown list"
            );
            assertNotNull(styleIntent);
            assertEquals(styleIntent.styleName(), "action");
            assertEquals(styleIntent.tone(), "warm");
            assertEquals(styleIntent.outputFormat(), "bullet");

            MemoryIntentNlu.CompressionIntent compressionIntent = MemoryIntentNlu.extractCompressionIntent(
                    "按我的风格压缩这段记忆：记录今日复盘，聚焦到retrospective"
            );
            assertNotNull(compressionIntent);
            assertEquals(compressionIntent.source(), "记录今日复盘");
            assertEquals(compressionIntent.focus(), "review");
        } finally {
            restoreProperty(PROP_FOCUS_REVIEW_TERMS, oldFocus);
            restoreProperty(PROP_STYLE_ACTION_TERMS, oldStyle);
            restoreProperty(PROP_TONE_WARM_TERMS, oldTone);
            restoreProperty(PROP_FORMAT_BULLET_TERMS, oldFormat);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }
}


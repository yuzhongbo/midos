package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @Test
    void shouldBuildStructuredPromptWithTopFiveMemoryItemsOnly() {
        PromptBuilder builder = new PromptBuilder();
        PromptMemoryContextDto context = new PromptMemoryContextDto(
                "user: raw conversation should not be dumped",
                "semantic memory",
                "procedural hints",
                Map.of("role", "assistant", "style", "direct"),
                List.of(
                        item("semantic", "alpha"),
                        item("procedural", "beta"),
                        item("semantic", "gamma"),
                        item("semantic", "delta"),
                        item("procedural", "epsilon"),
                        item("semantic", "zeta")
                )
        );

        String prompt = builder.build(context, "请总结当前任务");

        assertTrue(prompt.contains("[Assistant Role]"));
        assertTrue(prompt.contains("[User Profile]"));
        assertTrue(prompt.contains("[Current Task]"));
        assertTrue(prompt.contains("[Relevant Memory]"));
        assertTrue(prompt.contains("[User Query]"));
        assertTrue(prompt.contains("private assistant"));
        assertFalse(prompt.contains("raw conversation should not be dumped"));
        assertTrue(prompt.contains("epsilon"));
        assertFalse(prompt.contains("zeta"));
        assertTrue(PromptBuilder.estimateTokens(prompt) <= PromptBuilder.MAX_TOKENS);
    }

    @Test
    void shouldHumanizeTaskStateAndLearningSignalMarkers() {
        PromptBuilder builder = new PromptBuilder();
        PromptMemoryContextDto context = new PromptMemoryContextDto(
                "",
                "",
                "",
                Map.of(),
                List.of(
                        item("semantic", "[任务状态] 当前事项：提交周报；状态：进行中；下一步：继续推进"),
                        item("semantic-routing", "[学习信号] 当前事项：提交周报；偏好：上下文明确时直接推进，少澄清")
                )
        );

        String prompt = builder.build(context, "继续");

        assertFalse(prompt.contains("[任务状态]"));
        assertFalse(prompt.contains("[学习信号]"));
        assertTrue(prompt.contains("当前事项：提交周报"));
        assertTrue(prompt.contains("上下文明确时直接推进"));
    }

    private RetrievedMemoryItemDto item(String type, String text) {
        return new RetrievedMemoryItemDto(type, text, 0.9, 0.8, 0.9, 0.9, 1L);
    }
}

package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveTaskResolverTest {

    @Test
    void shouldResolveTaskThreadFromPromptMemoryMarkers() {
        ActiveTaskResolver resolver = new ActiveTaskResolver(null);
        PromptMemoryContextDto context = new PromptMemoryContextDto(
                "",
                """
                - [fact] [任务事实] 当前事项：提交周报；项目：运营周报；截止时间：周五前
                - [working] [任务状态] 当前事项：提交周报；状态：进行中；下一步：同步项目风险
                - [assistant-context] [学习信号] 当前事项：提交周报；偏好：上下文明确时直接推进，少澄清
                """,
                "",
                Map.of(),
                List.of(
                        new RetrievedMemoryItemDto("semantic", "[任务事实] 当前事项：提交周报；项目：运营周报；截止时间：周五前", 0.9, 0.9, 0.9, 0.9, 1L)
                )
        );

        ActiveTaskResolver.ResolvedTaskThread resolved = resolver.resolve("u1", "继续", context);

        assertEquals("提交周报", resolved.focus());
        assertEquals("进行中", resolved.state());
        assertEquals("同步项目风险", resolved.nextAction());
        assertEquals("运营周报", resolved.project());
        assertEquals("周五前", resolved.dueDate());
        assertEquals("上下文明确时直接推进，少澄清", resolved.preferenceHint());
    }

    @Test
    void shouldAppendTaskThreadIntoMemoryContext() {
        ActiveTaskResolver resolver = new ActiveTaskResolver(null);
        ActiveTaskResolver.ResolvedTaskThread resolved = new ActiveTaskResolver.ResolvedTaskThread(
                "提交周报",
                "进行中",
                "同步项目风险",
                "运营周报",
                "周五前",
                "上下文明确时直接推进，少澄清",
                "当前事项 提交周报；状态 进行中；下一步 同步项目风险"
        );

        String memoryContext = resolver.enrichMemoryContext("Recent conversation:\n- user: 继续", resolved, 800);

        assertTrue(memoryContext.contains("Active task thread:"));
        assertTrue(memoryContext.contains("当前事项：提交周报"));
        assertTrue(memoryContext.contains("偏好：上下文明确时直接推进，少澄清"));
    }
}

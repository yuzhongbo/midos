package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImGatewayServiceTest {

    @Test
    void shouldReturnKeyPointReviewAfterAffirmativeFollowUp() {
        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        ImGatewayService service = new ImGatewayService(dispatcherService, memoryManager, consolidationService);

        MemoryCompressionPlan plan = new MemoryCompressionPlan(
                new MemoryStyleProfile("concise", "direct", "plain"),
                List.of(new MemoryCompressionStep("STYLED", "先执行计划。", 6)),
                Instant.now()
        );
        when(memoryManager.buildMemoryCompressionPlan(eq("im:dingtalk:u1"), any(), any(), any())).thenReturn(plan);

        String first = service.chat(
                ImPlatform.DINGTALK,
                "u1",
                "c1",
                "按我的风格压缩这段记忆：今天18:30前必须提交合同，不要遗漏附件"
        );
        assertTrue(first.contains("如果你愿意，我可以再列出原文关键点"));

        String second = service.chat(ImPlatform.DINGTALK, "u1", "c1", "好的");
        assertTrue(second.contains("原文关键点"));
        assertTrue(second.contains("必须提交合同"));

        String third = service.chat(ImPlatform.DINGTALK, "u1", "c1", "生成待办");
        assertTrue(third.contains("执行清单"));
        assertTrue(third.contains("今天（today）"));
        assertTrue(third.contains("P1"));
        assertTrue(third.contains("必须提交合同"));
    }

    @Test
    void shouldFallbackToDispatcherWhenNoPendingReviewExists() {
        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        ImGatewayService service = new ImGatewayService(dispatcherService, memoryManager, consolidationService);

        when(dispatcherService.dispatch(eq("im:wechat:u2"), eq("要"), any()))
                .thenReturn(new DispatchResult("normal", "echo"));

        String reply = service.chat(ImPlatform.WECHAT, "u2", "c2", "要");

        assertTrue(reply.contains("normal"));
        verify(dispatcherService).dispatch(eq("im:wechat:u2"), eq("要"), any());
    }
}



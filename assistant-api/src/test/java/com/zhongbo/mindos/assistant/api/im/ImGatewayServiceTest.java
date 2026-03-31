package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImGatewayServiceTest {

    @AfterEach
    void clearTodoPolicyProperties() {
        System.clearProperty("mindos.todo.priority.p1-threshold");
        System.clearProperty("mindos.todo.priority.p2-threshold");
        System.clearProperty("mindos.todo.window.p1");
        System.clearProperty("mindos.todo.window.p2");
        System.clearProperty("mindos.todo.window.p3");
        System.clearProperty("mindos.todo.legend");
    }

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
        assertTrue(third.contains("优先级说明：P1=今天必须完成"));
        assertTrue(third.contains("当前待办策略：P1>= 45，P2>= 25"));
        assertTrue(third.contains("今天（today）"));
        assertTrue(third.contains("P1"));
        assertTrue(third.contains("建议24小时内完成"));
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

    @Test
    void shouldApplyConfiguredTodoPolicy() {
        System.setProperty("mindos.todo.priority.p1-threshold", "100");
        System.setProperty("mindos.todo.priority.p2-threshold", "10");
        System.setProperty("mindos.todo.window.p2", "建议两天内完成");
        System.setProperty("mindos.todo.legend", "优先级说明：按团队自定义策略执行。");

        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        ImGatewayService service = new ImGatewayService(dispatcherService, memoryManager, consolidationService);

        MemoryCompressionPlan plan = new MemoryCompressionPlan(
                new MemoryStyleProfile("concise", "direct", "plain"),
                List.of(new MemoryCompressionStep("STYLED", "先执行计划。", 6)),
                Instant.now()
        );
        when(memoryManager.buildMemoryCompressionPlan(eq("im:dingtalk:u3"), any(), any(), any())).thenReturn(plan);

        service.chat(ImPlatform.DINGTALK, "u3", "c3", "按我的风格压缩这段记忆：今天18:30前必须提交合同，不要遗漏附件");
        service.chat(ImPlatform.DINGTALK, "u3", "c3", "好的");
        String todo = service.chat(ImPlatform.DINGTALK, "u3", "c3", "生成待办");

        assertTrue(todo.contains("优先级说明：按团队自定义策略执行。"));
        assertTrue(todo.contains("当前待办策略：P1>= 100，P2>= 10"));
        assertTrue(todo.contains("P2"));
        assertTrue(todo.contains("建议两天内完成"));
    }

    @Test
    void shouldMarkTaskCompletedWhenOpenApiFallbackPushSucceeds() {
        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        DingtalkAsyncReplyClient asyncReplyClient = mock(DingtalkAsyncReplyClient.class);
        DingtalkOpenApiMessageClient openApiMessageClient = mock(DingtalkOpenApiMessageClient.class);
        ImGatewayService service = new ImGatewayService(
                dispatcherService,
                memoryManager,
                consolidationService,
                asyncReplyClient,
                openApiMessageClient
        );

        when(openApiMessageClient.sendText(eq("ding-u4"), eq("conv-u4"), contains("最终结果")))
                .thenReturn(true);

        assertTrue(service.tryPushViaDingtalkOpenApi("im:dingtalk:ding-u4", "task-4", "ding-u4", "conv-u4", "最终结果"));
        verify(openApiMessageClient).sendText(eq("ding-u4"), eq("conv-u4"), contains("最终结果"));
        verify(memoryManager).updateLongTaskProgress(
                eq("im:dingtalk:ding-u4"),
                eq("task-4"),
                eq("im-dingtalk-openapi"),
                eq("回推钉钉结果"),
                eq("已通过钉钉 OpenAPI 主动补发结果"),
                eq(""),
                any(),
                eq(true)
        );
    }

    @Test
    void shouldKeepCompensationFallbackWhenOpenApiPushFails() {
        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        DingtalkAsyncReplyClient asyncReplyClient = mock(DingtalkAsyncReplyClient.class);
        DingtalkOpenApiMessageClient openApiMessageClient = mock(DingtalkOpenApiMessageClient.class);
        ImGatewayService service = new ImGatewayService(
                dispatcherService,
                memoryManager,
                consolidationService,
                asyncReplyClient,
                openApiMessageClient
        );

        when(openApiMessageClient.sendText(any(), any(), any())).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertFalse(service.tryPushViaDingtalkOpenApi(
                "im:dingtalk:ding-u5",
                "task-5",
                "ding-u5",
                "conv-u5",
                "最终结果"
        ));
        verify(memoryManager, never()).updateLongTaskProgress(
                eq("im:dingtalk:ding-u5"),
                eq("task-5"),
                eq("im-dingtalk-openapi"),
                eq("回推钉钉结果"),
                eq("已通过钉钉 OpenAPI 主动补发结果"),
                eq(""),
                any(),
                eq(true)
        );
    }
}


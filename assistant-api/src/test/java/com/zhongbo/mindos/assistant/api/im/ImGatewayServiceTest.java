package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.ImDegradedReplyMarker;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    void shouldSanitizeLeakedSkeletonPromptFromDispatcherReply() {
        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        ImGatewayService service = new ImGatewayService(dispatcherService, memoryManager, consolidationService);

        when(dispatcherService.dispatch(eq("im:dingtalk:u4"), eq("优化记忆"), any()))
                .thenReturn(new DispatchResult(
                        "[LLM gemini] skeleton response for user im:dingtalk:u4: Answer naturally using the context when helpful.\n"
                                + "Recent conversation:\n"
                                + "- user: 你好测试一下\n"
                                + "Relevant knowledge:\n"
                                + "- none\n"
                                + "User skill habits:\n"
                                + "- none\n"
                                + "User input: 优化记忆",
                        "llm"
                ));

        Logger logger = Logger.getLogger(ImGatewayService.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);

        String reply;
        try {
            reply = service.chat(ImPlatform.DINGTALK, "u4", "c4", "优化记忆");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertEquals(ImReplySanitizer.FRIENDLY_IM_FALLBACK_REPLY, reply);
        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"im.reply.degraded\""));
        assertTrue(logs.contains("\"platform\":\"dingtalk\""));
        assertTrue(logs.contains("\"replySource\":\"dispatcher:llm\""));
        assertTrue(logs.contains("\"provider\":\"gemini\""));
        assertTrue(logs.contains("\"errorCategory\":\"unavailable\""));
        assertTrue(logs.contains("\"fallbackKind\":\"friendly\""));
        assertTrue(logs.contains("llm_marker"));
        assertTrue(logs.contains("skeleton_mode"));
        assertTrue(logs.contains("prompt_template_leak"));
    }

    @Test
    void shouldCompleteAsyncDingtalkReplyThroughDispatcher() throws Exception {
        DispatcherService dispatcherService = mock(DispatcherService.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        ImGatewayService service = new ImGatewayService(dispatcherService, memoryManager, consolidationService);

        when(dispatcherService.dispatchAsync(eq("im:dingtalk:u5"), eq("继续生成"), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new DispatchResult(
                        ImDegradedReplyMarker.encode("gemini", "timeout"),
                        "llm"
                )));

        String reply = service.chatAsync(ImPlatform.DINGTALK, "u5", "c5", "继续生成").get(1, TimeUnit.SECONDS);

        assertEquals(ImReplySanitizer.TIMEOUT_IM_FALLBACK_REPLY, reply);
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

    private static final class CapturingHandler extends Handler {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String joinedMessages() {
            return String.join("\n", messages);
        }
    }
}



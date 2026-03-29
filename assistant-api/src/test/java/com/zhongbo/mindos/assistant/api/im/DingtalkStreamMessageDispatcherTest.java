package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DingtalkStreamMessageDispatcherTest {

    private DingtalkStreamMessageDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    void shouldSendWaitingStatusThenFinalReplyForSlowMessage() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                10L,
                "我正在处理中，请稍等。",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        CompletableFuture<String> replyFuture = new CompletableFuture<>();
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-1", "cid-1", "帮我总结今天安排"))
                .thenReturn(replyFuture);
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);
        Logger logger = Logger.getLogger(DingtalkStreamMessageDispatcher.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);

        try {
            dispatcher.handleIncomingPayload(Map.of(
                    "conversationId", "cid-1",
                    "senderStaffId", "staff-1",
                    "text", Map.of("content", "帮我总结今天安排")
            ));

            waitUntil(() -> !sender.messages.isEmpty(), 1000);
            replyFuture.complete("这是最终答复");
            waitUntil(() -> sender.messages.size() >= 2, 1000);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertEquals("我正在处理中，请稍等。", sender.messages.get(0));
        assertEquals("这是最终答复", sender.messages.get(1));
        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.received\""));
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.waiting.sent\""));
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.final.sent\""));
    }

    @Test
    void shouldSkipWaitingStatusWhenReplyIsFast() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                200L,
                "我正在处理中，请稍等。",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-2", "cid-2", "你好"))
                .thenReturn(CompletableFuture.completedFuture("你好，我在。"));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-2",
                "senderStaffId", "staff-2",
                "text", Map.of("content", "你好")
        ));

        waitUntil(() -> !sender.messages.isEmpty(), 1000);
        TimeUnit.MILLISECONDS.sleep(250L);

        assertEquals(1, sender.messages.size());
        assertEquals("你好，我在。", sender.messages.get(0));
    }

    @Test
    void shouldSuppressRecentBotEchoesToAvoidLoops() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                0L,
                "处理中",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-3", "cid-3", "第一次问题"))
                .thenReturn(CompletableFuture.completedFuture("最终回复"));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-3",
                "senderStaffId", "staff-3",
                "text", Map.of("content", "第一次问题")
        ));
        waitUntil(() -> sender.messages.contains("最终回复"), 1000);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-3",
                "senderStaffId", "robot-self",
                "text", Map.of("content", "最终回复")
        ));
        TimeUnit.MILLISECONDS.sleep(150L);

        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.contains("最终回复"));
    }

    @Test
    void shouldReadConversationPayloadFromWrappedDataNode() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                200L,
                "我正在处理中，请稍等。",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-4", "cid-4", "帮我列待办"))
                .thenReturn(CompletableFuture.completedFuture("好的，给你三条待办"));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);

        dispatcher.handleIncomingPayload(Map.of(
                "eventType", "chatbot.message",
                "data", Map.of(
                        "conversationId", "cid-4",
                        "senderStaffId", "staff-4",
                        "sessionWebhook", "https://oapi.dingtalk.com/robot/send?access_token=test",
                        "text", Map.of("content", "帮我列待办")
                )
        ));

        waitUntil(() -> !sender.messages.isEmpty(), 1000);
        assertEquals(1, sender.messages.size());
        assertEquals("好的，给你三条待办", sender.messages.get(0));
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=test", sender.lastSessionWebhook);
    }

    @Test
    void shouldSplitOversizedFinalReplyBeforeSend() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        String longReply = "1234567890".repeat(45);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                50L,
                "处理中",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-cap", "cid-cap", "请总结"))
                .thenReturn(CompletableFuture.completedFuture(longReply));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);
        setPrivateIntField(dispatcher, "dingtalkReplyMaxChars", 200);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-cap",
                "senderStaffId", "staff-cap",
                "text", Map.of("content", "请总结")
        ));

        waitUntil(() -> sender.messages.size() >= 3, 1000);
        assertTrue(sender.messages.stream().allMatch(message -> message.length() <= 200));
        assertEquals(longReply, String.join("", sender.messages));
    }

    @Test
    void shouldForceWaitingStatusEvenForFastReplyWhenConfigured() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                500L,
                "处理中",
                true,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-force", "cid-force", "你好"))
                .thenReturn(CompletableFuture.completedFuture("最终回复"));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-force",
                "senderStaffId", "staff-force",
                "text", Map.of("content", "你好")
        ));

        waitUntil(() -> sender.messages.size() >= 2, 1000);
        assertEquals("处理中", sender.messages.get(0));
        assertEquals("最终回复", sender.messages.get(1));
    }

    @Test
    void shouldSendTimeoutFallbackWhenAsyncReplyNeverCompletes() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                10L,
                "处理中",
                true,
                60L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        when(gatewayService.chatAsync(ImPlatform.DINGTALK, "staff-timeout", "cid-timeout", "慢一点"))
                .thenReturn(new CompletableFuture<>());
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-timeout",
                "senderStaffId", "staff-timeout",
                "text", Map.of("content", "慢一点")
        ));

        waitUntil(() -> sender.messages.size() >= 2, 1500);
        assertEquals("处理中", sender.messages.get(0));
        assertEquals(ImReplySanitizer.TIMEOUT_IM_FALLBACK_REPLY, sender.messages.get(1));
    }

    private void setPrivateIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private void waitUntil(CheckedCondition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue(condition.test(), "condition was not met before timeout");
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }

    private static final class RecordingConversationSender implements DingtalkConversationSender {

        private final boolean ready;
        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        private volatile String lastSessionWebhook;

        private RecordingConversationSender(boolean ready) {
            this.ready = ready;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public boolean sendText(String openConversationId, String text) {
            messages.add(text);
            return true;
        }

        @Override
        public boolean sendText(String openConversationId, String text, String sessionWebhook) {
            lastSessionWebhook = sessionWebhook;
            return sendText(openConversationId, text);
        }
    }

    private static final class CapturingHandler extends Handler {

        private final java.util.List<String> messages = new ArrayList<>();

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


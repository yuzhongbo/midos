package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(gatewayService.chatAsync("staff-1", "cid-1", "帮我总结今天安排"))
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
        when(gatewayService.chatAsync("staff-2", "cid-2", "你好"))
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
        Map<String, Object> stats = dispatcher.streamStatsSnapshot();
        assertTrue(((Number) stats.get("waitingSuppressedDecisions")).longValue() >= 1L);
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
        when(gatewayService.chatAsync("staff-3", "cid-3", "第一次问题"))
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
        when(gatewayService.chatAsync("staff-4", "cid-4", "帮我列待办"))
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
        when(gatewayService.chatAsync("staff-cap", "cid-cap", "请总结"))
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
        when(gatewayService.chatAsync("staff-force", "cid-force", "你好"))
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
    void shouldSendRealFinalReplyAfterTimeoutNoticeWhenAsyncReplyIsSlow() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        CompletableFuture<String> slowReply = new CompletableFuture<>();
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
        when(gatewayService.chatAsync("staff-timeout", "cid-timeout", "慢一点"))
                .thenReturn(slowReply);
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-timeout",
                "senderStaffId", "staff-timeout",
                "text", Map.of("content", "慢一点")
        ));

        waitUntil(() -> sender.messages.size() >= 1, 1500);
        TimeUnit.MILLISECONDS.sleep(1_100L);
        slowReply.complete("最终结果已生成");
        waitUntil(() -> sender.messages.size() >= 2, 1500);

        assertEquals("处理中", sender.messages.get(0));
        assertEquals("感谢等待，以下是完整回复：\n最终结果已生成", sender.messages.get(1));
    }

    @Test
    void shouldUpdateSameCardInPlaceWhenStreamingCardUpdateIsEnabled() throws Exception {
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
        when(gatewayService.chatStream(eq(ImPlatform.DINGTALK), eq("staff-card"), eq("cid-card"), eq("给我看进展"), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<String> deltaConsumer = invocation.getArgument(4);
                    deltaConsumer.accept("第一段");
                    deltaConsumer.accept("，第二段");
                    return CompletableFuture.completedFuture("第一段，第二段");
                });
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);
        setPrivateBooleanField(dispatcher, "dingtalkCardEnabled", true);
        setPrivateBooleanField(dispatcher, "dingtalkMessageUpdateEnabled", true);
        setPrivateBooleanField(dispatcher, "dingtalkAgentStatusEnabled", true);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-card",
                "senderStaffId", "staff-card",
                "text", Map.of("content", "给我看进展")
        ));

        waitUntil(() -> {
            if (sender.messages.size() != 1) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> perConversation = (Map<String, Object>) dispatcher.streamStatsSnapshot().get("cardUpdatesPerConversation");
            return perConversation != null && !perConversation.isEmpty();
        }, 1000);

        assertEquals(1, sender.messages.size());
        assertTrue(sender.messages.get(0).contains("已完成"));
        assertTrue(sender.messages.get(0).contains("第一段"));
        assertTrue(sender.updateCalls >= 1);
        assertEquals("", sender.lastSessionWebhook);

        Map<String, Object> stats = dispatcher.streamStatsSnapshot();
        assertTrue(((Number) stats.get("firstStatusSamples")).longValue() >= 1L);
        assertTrue(((Number) stats.get("firstTokenSamples")).longValue() >= 1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> perConversation = (Map<String, Object>) stats.get("cardUpdatesPerConversation");
        assertTrue(!perConversation.isEmpty());
    }

    @Test
    void shouldNotSendFastTrackWaitingForQuickStreamWhenForceWaitingDisabled() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                300L,
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
        when(gatewayService.chatAsync("staff-quick", "cid-quick", "测速"))
                .thenReturn(CompletableFuture.completedFuture("网络正常"));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);
        setPrivateBooleanField(dispatcher, "dingtalkCardEnabled", false);
        setPrivateBooleanField(dispatcher, "dingtalkMessageUpdateEnabled", false);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-quick",
                "senderStaffId", "staff-quick",
                "text", Map.of("content", "测速")
        ));

        waitUntil(() -> !sender.messages.isEmpty(), 1000);
        TimeUnit.MILLISECONDS.sleep(400L);

        assertEquals(1, sender.messages.size());
        assertEquals("网络正常", sender.messages.get(0));
    }

    @Test
    void shouldSendNaturalFinalReplyWhenWaitingFallbackCannotUpdateInPlace() throws Exception {
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
                "已收到，正在处理",
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
        when(gatewayService.chatAsync("staff-natural", "cid-natural", "测速"))
                .thenReturn(CompletableFuture.completedFuture("服务器网络正常，延迟较低。"));
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);
        setPrivateBooleanField(dispatcher, "dingtalkCardEnabled", false);
        setPrivateBooleanField(dispatcher, "dingtalkMessageUpdateEnabled", true);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-natural",
                "senderStaffId", "staff-natural",
                "text", Map.of("content", "测速")
        ));

        waitUntil(() -> sender.messages.size() >= 2, 1000);

        assertEquals("已收到，正在处理", sender.messages.get(0));
        assertEquals("服务器网络正常，延迟较低。", sender.messages.get(1));
    }

    @Test
    void shouldSuppressWaitingForShortLightRequestEvenWhenReplyIsSlow() throws Exception {
        ImGatewayService gatewayService = mock(ImGatewayService.class);
        RecordingConversationSender sender = new RecordingConversationSender(true);
        CompletableFuture<String> replyFuture = new CompletableFuture<>();
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                10L,
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
        when(gatewayService.chatAsync("staff-light", "cid-light", "测速"))
                .thenReturn(replyFuture);
        dispatcher = new DingtalkStreamMessageDispatcher(gatewayService, sender, settings);
        setPrivateBooleanField(dispatcher, "dingtalkCardEnabled", false);
        setPrivateBooleanField(dispatcher, "dingtalkMessageUpdateEnabled", false);

        dispatcher.handleIncomingPayload(Map.of(
                "conversationId", "cid-light",
                "senderStaffId", "staff-light",
                "text", Map.of("content", "测速")
        ));

        TimeUnit.MILLISECONDS.sleep(120L);
        assertEquals(0, sender.messages.size());

        replyFuture.complete("服务器网络正常，延迟较低。");
        waitUntil(() -> sender.messages.size() == 1, 1000);

        assertEquals("服务器网络正常，延迟较低。", sender.messages.get(0));
        Map<String, Object> stats = dispatcher.streamStatsSnapshot();
        assertTrue(((Number) stats.get("waitingSuppressedDecisions")).longValue() >= 1L);
    }

    private void setPrivateIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private void setPrivateBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
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
        private volatile int updateCalls;

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

        @Override
        public boolean sendMarkdownCard(String openConversationId, String title, String markdown, String sessionWebhook) {
            return sendMarkdownCardHandle(openConversationId, title, markdown, sessionWebhook).sent();
        }

        @Override
        public DingtalkMessageHandle sendMarkdownCardHandle(String openConversationId,
                                                            String title,
                                                            String markdown,
                                                            String sessionWebhook) {
            lastSessionWebhook = sessionWebhook;
            messages.add(markdown);
            return DingtalkMessageHandle.updatable(openConversationId, sessionWebhook, String.valueOf(messages.size() - 1));
        }

        @Override
        public boolean updateMessage(DingtalkMessageHandle handle,
                                     String title,
                                     String markdown,
                                     String sessionWebhook) {
            lastSessionWebhook = sessionWebhook;
            int index = Integer.parseInt(handle.platformMessageId());
            messages.set(index, markdown);
            updateCalls++;
            return true;
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


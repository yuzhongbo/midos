package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DingtalkOpenApiConversationSenderTest {

    @Test
    void shouldLogTokenFailureReasonWhenDingtalkReturnsErrorCode() throws Exception {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "stream-key",
                "stream-secret",
                "chatbot",
                800L,
                "我正在处理这条消息，请稍等，我会继续回复你。",
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
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"errcode\":40014,\"errmsg\":\"invalid app credentials\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResponse);
        DingtalkOpenApiConversationSender sender = new DingtalkOpenApiConversationSender(
                settings,
                new ObjectMapper().findAndRegisterModules(),
                httpClient
        );

        Logger logger = Logger.getLogger(DingtalkOpenApiConversationSender.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);

        boolean sent;
        try {
            sent = sender.sendText("cid-100", "测试一下最终回复");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertFalse(sent);
        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.token.failed\""));
        assertTrue(logs.contains("\"reason\":\"errcode\""));
        assertTrue(logs.contains("invalid app credentials"));
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.outbound.exception\""));
    }

    @Test
    void shouldLogIdentifierFlagsWhenUpdatingMessage() throws Exception {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "stream-key",
                "stream-secret",
                "chatbot",
                800L,
                "我正在处理这条消息，请稍等，我会继续回复你。",
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
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"errcode\":0,\"access_token\":\"token-123\",\"expires_in\":7200}");
        @SuppressWarnings("unchecked")
        HttpResponse<String> updateResponse = mock(HttpResponse.class);
        when(updateResponse.statusCode()).thenReturn(200);
        when(updateResponse.body()).thenReturn("{\"success\":true,\"data\":{\"messageId\":\"msg-1\",\"processQueryKey\":\"pqk-2\",\"outTrackId\":\"ot-3\"}}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResponse)
                .thenReturn(updateResponse);

        DingtalkOpenApiConversationSender sender = new DingtalkOpenApiConversationSender(
                settings,
                new ObjectMapper().findAndRegisterModules(),
                httpClient
        );

        Logger logger = Logger.getLogger(DingtalkOpenApiConversationSender.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);

        boolean updated;
        try {
            updated = sender.updateMessage(
                    DingtalkMessageHandle.updatable("cid-200", "", "mid-raw"),
                    "MindOS 处理中",
                    "流式内容",
                    ""
            );
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertTrue(updated);
        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.outbound.updated\""));
        assertTrue(logs.contains("\"hasMessageId\":true"));
        assertTrue(logs.contains("\"hasProcessQueryKey\":true"));
        assertTrue(logs.contains("\"hasOutTrackId\":true"));
        assertTrue(logs.contains("\"selectedIdSource\":\"messageId\""));

        var debugSnapshot = sender.outboundDebugSnapshot();
        assertTrue(debugSnapshot.containsKey("lastUpdate"));
    }

    @Test
    void shouldLogHintWhenOutboundSendReturnsInvalidActionNotFound() throws Exception {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "stream-key",
                "stream-secret",
                "chatbot",
                800L,
                "我正在处理这条消息，请稍等，我会继续回复你。",
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
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"errcode\":0,\"access_token\":\"token-123\",\"expires_in\":7200}");
        @SuppressWarnings("unchecked")
        HttpResponse<String> sendResponse = mock(HttpResponse.class);
        when(sendResponse.statusCode()).thenReturn(404);
        when(sendResponse.body()).thenReturn("{\"code\":\"InvalidAction.NotFound\",\"message\":\"Specified api is not found\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResponse)
                .thenReturn(sendResponse)
                .thenReturn(sendResponse);

        DingtalkOpenApiConversationSender sender = new DingtalkOpenApiConversationSender(
                settings,
                new ObjectMapper().findAndRegisterModules(),
                httpClient
        );

        Logger logger = Logger.getLogger(DingtalkOpenApiConversationSender.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);

        boolean sent;
        try {
            sent = sender.sendText("cid-404", "测试 404 提示");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertFalse(sent);
        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"dingtalk.stream.outbound.failed\""));
        assertTrue(logs.contains("InvalidAction.NotFound"));
        assertTrue(logs.contains("send-url not supported"));
    }

    @Test
    void shouldFallbackToSendToConversationWhenChatSendReturnsInvalidAction() throws Exception {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "stream-key",
                "stream-secret",
                "chatbot",
                800L,
                "我正在处理这条消息，请稍等，我会继续回复你。",
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
        HttpClient httpClient = mock(HttpClient.class);

        @SuppressWarnings("unchecked")
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"errcode\":0,\"access_token\":\"token-123\",\"expires_in\":7200}");

        @SuppressWarnings("unchecked")
        HttpResponse<String> send404Response = mock(HttpResponse.class);
        when(send404Response.statusCode()).thenReturn(404);
        when(send404Response.body()).thenReturn("{\"code\":\"InvalidAction.NotFound\",\"message\":\"Specified api is not found\"}");

        @SuppressWarnings("unchecked")
        HttpResponse<String> fallbackSuccessResponse = mock(HttpResponse.class);
        when(fallbackSuccessResponse.statusCode()).thenReturn(200);
        when(fallbackSuccessResponse.body()).thenReturn("{\"success\":true,\"data\":{\"messageId\":\"msg-fallback\"}}");

        AtomicInteger sendCallCount = new AtomicInteger(0);
        List<String> requestPaths = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requestPaths.add(request.uri().getPath());
                    String path = request.uri().getPath();
                    if (path.contains("gettoken")) {
                        return tokenResponse;
                    }
                    if (sendCallCount.getAndIncrement() == 0) {
                        return send404Response;
                    }
                    return fallbackSuccessResponse;
                });

        DingtalkOpenApiConversationSender sender = new DingtalkOpenApiConversationSender(
                settings,
                new ObjectMapper().findAndRegisterModules(),
                httpClient
        );

        Logger logger = Logger.getLogger(DingtalkOpenApiConversationSender.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);

        boolean sent;
        try {
            sent = sender.sendText("cid-fallback", "测试自动 fallback");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        assertTrue(sent);
        assertTrue(requestPaths.stream().anyMatch(path -> path.contains("/im/chat/messages/send")));
        assertTrue(requestPaths.stream().anyMatch(path -> path.contains("/im/messages/sendToConversation")));
        String logs = handler.joinedMessages();
        assertTrue(logs.contains("dingtalk.stream.outbound.fallback.attempt"));

        var debugSnapshot = sender.outboundDebugSnapshot();
        @SuppressWarnings("unchecked")
        var primary = (java.util.Map<String, Object>) debugSnapshot.get("lastPrimaryAttempt");
        @SuppressWarnings("unchecked")
        var fallback = (java.util.Map<String, Object>) debugSnapshot.get("lastFallbackAttempt");
        assertNotNull(primary);
        assertNotNull(fallback);
        assertTrue(String.valueOf(primary.get("requestUrl")).contains("/im/chat/messages/send"));
        assertTrue(String.valueOf(primary.get("apiType")).contains("chat_messages_send"));
        assertTrue(String.valueOf(primary.get("status")).contains("404"));
        assertTrue(String.valueOf(fallback.get("requestUrl")).contains("/im/messages/sendToConversation"));
        assertTrue(String.valueOf(fallback.get("apiType")).contains("send_to_conversation"));
        assertTrue(String.valueOf(fallback.get("status")).contains("200"));
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


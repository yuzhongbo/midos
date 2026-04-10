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
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(new StubHttpResponse(200, "{\"errcode\":40014,\"errmsg\":\"invalid app credentials\"}"));
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
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(new StubHttpResponse(200, "{\"errcode\":0,\"access_token\":\"token-123\",\"expires_in\":7200}"));
        httpClient.enqueueResponse(new StubHttpResponse(200, "{\"success\":true,\"data\":{\"messageId\":\"msg-1\",\"processQueryKey\":\"pqk-2\",\"outTrackId\":\"ot-3\"}}"));

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
        TestHttpClient httpClient = new TestHttpClient();
        httpClient.enqueueResponse(new StubHttpResponse(200, "{\"errcode\":0,\"access_token\":\"token-123\",\"expires_in\":7200}"));
        httpClient.enqueueResponse(new StubHttpResponse(404, "{\"code\":\"InvalidAction.NotFound\",\"message\":\"Specified api is not found\"}"));
        httpClient.enqueueResponse(new StubHttpResponse(404, "{\"code\":\"InvalidAction.NotFound\",\"message\":\"Specified api is not found\"}"));

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
        TestHttpClient httpClient = new TestHttpClient();

        HttpResponse<String> tokenResponse = new StubHttpResponse(200, "{\"errcode\":0,\"access_token\":\"token-123\",\"expires_in\":7200}");

        HttpResponse<String> send404Response = new StubHttpResponse(404, "{\"code\":\"InvalidAction.NotFound\",\"message\":\"Specified api is not found\"}");

        HttpResponse<String> fallbackSuccessResponse = new StubHttpResponse(200, "{\"success\":true,\"data\":{\"messageId\":\"msg-fallback\"}}");

        AtomicInteger sendCallCount = new AtomicInteger(0);
        List<String> requestPaths = new ArrayList<>();
        httpClient.setResponder(request -> {
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

    private static final class TestHttpClient extends HttpClient {
        private final java.util.Queue<Object> outcomes = new java.util.ArrayDeque<>();
        private java.util.function.Function<HttpRequest, Object> responder;

        void enqueueResponse(Object outcome) { outcomes.add(outcome); }

        void setResponder(java.util.function.Function<HttpRequest, Object> responder) { this.responder = responder; }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() {
            return java.util.Optional.of(java.time.Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws java.io.IOException {
            Object outcome;
            if (responder != null) outcome = responder.apply(request);
            else outcome = outcomes.poll();
            if (outcome instanceof java.io.IOException io) throw io;
            return (HttpResponse<T>) outcome;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("not used in tests"));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("not used in tests"));
        }
    }

    private static final class StubHttpResponse implements HttpResponse<String> {
        private final int statusCode;
        private final String body;

        private StubHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(java.net.URI.create("https://example.com")).build();
        }

        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return java.net.URI.create("https://example.com");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}


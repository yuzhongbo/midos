package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
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


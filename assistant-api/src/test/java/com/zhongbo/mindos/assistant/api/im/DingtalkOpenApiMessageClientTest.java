package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DingtalkOpenApiMessageClientTest {

    private static final AtomicInteger TOKEN_COUNT = new AtomicInteger();
    private static final AtomicInteger CONVERSATION_COUNT = new AtomicInteger();
    private static final AtomicInteger USER_COUNT = new AtomicInteger();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");
    private static HttpServer server;

    @BeforeAll
    static void start() {
        server = startServer();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void resetCounters() {
        TOKEN_COUNT.set(0);
        CONVERSATION_COUNT.set(0);
        USER_COUNT.set(0);
        LAST_AUTH.set("");
        LAST_BODY.set("");
    }

    @Test
    void shouldAcquireTokenAndSendConversationText() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DingtalkOpenApiMessageClient client = new DingtalkOpenApiMessageClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofSeconds(5),
                true,
                "test-app-key",
                "test-app-secret",
                "robot-code",
                baseUrl + "/token",
                baseUrl + "/conversation",
                baseUrl + "/users",
                DingtalkOpenApiMessageClient.SendMode.CONVERSATION_FIRST,
                60L,
                List.of("127.0.0.1"),
                true
        );

        assertTrue(client.isEnabled());
        assertTrue(client.sendText("ding-user", "conv-001", "hello openapi"));
        assertTrue(TOKEN_COUNT.get() >= 1);
        assertTrue(CONVERSATION_COUNT.get() >= 1);
        assertTrue(LAST_AUTH.get().contains("token-123"));
        assertTrue(LAST_BODY.get().contains("\"openConversationId\":\"conv-001\""));
        assertTrue(LAST_BODY.get().contains("hello openapi"));
    }

    @Test
    void shouldPreferBatchUserSendWhenConfigured() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DingtalkOpenApiMessageClient client = new DingtalkOpenApiMessageClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofSeconds(5),
                true,
                "test-app-key",
                "test-app-secret",
                "robot-code",
                baseUrl + "/token",
                baseUrl + "/conversation",
                baseUrl + "/users",
                DingtalkOpenApiMessageClient.SendMode.USER_FIRST,
                60L,
                List.of("127.0.0.1"),
                true
        );

        assertTrue(client.sendText("ding-user-2", "conv-002", "hello user first"));
        assertTrue(USER_COUNT.get() >= 1);
        assertTrue(CONVERSATION_COUNT.get() == 0);
        assertTrue(LAST_BODY.get().contains("\"userIds\":[\"ding-user-2\"]"));
        assertTrue(LAST_BODY.get().contains("hello user first"));
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                TOKEN_COUNT.incrementAndGet();
                byte[] response = "{\"accessToken\":\"token-123\",\"expireIn\":7200}".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/conversation", exchange -> {
                CONVERSATION_COUNT.incrementAndGet();
                LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes()));
                byte[] response = "{\"success\":true}".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/users", exchange -> {
                USER_COUNT.incrementAndGet();
                LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes()));
                byte[] response = "{\"success\":true}".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to start DingTalk OpenAPI test server", ex);
        }
    }
}

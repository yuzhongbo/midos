package com.zhongbo.mindos.assistant.api.news;

import com.zhongbo.mindos.assistant.common.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsPushServiceTest {

    private HttpServer server;
    private String feedUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rss", new FixedResponseHandler(sampleRss()));
        server.start();
        feedUrl = "http://localhost:" + server.getAddress().getPort() + "/rss";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchSummarizeAndDeliver() {
        RecordingDelivery delivery = new RecordingDelivery(true);
        NewsPushConfig cfg = new NewsPushConfig(
                true,
                List.of(feedUrl),
                5,
                "0 0 9 * * *",
                "UTC",
                "",
                "conv-1",
                "sender-1",
                1200
        );
        NewsPushService service = new NewsPushService(
                HttpClient.newHttpClient(),
                delivery,
                new FixedLlmClient("LLM summary"),
                Clock.fixed(Instant.parse("2024-01-01T08:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5),
                cfg
        );
        NewsPushResult result = service.pushOnce();

        assertTrue(result.delivered());
        assertEquals(2, result.usedCount());
        assertTrue(delivery.lastMessage.contains("LLM summary"));
        assertTrue(delivery.lastMessage.contains("Title A"));
    }

    @Test
    void shouldFallbackWhenLlmFails() {
        RecordingDelivery delivery = new RecordingDelivery(true);
        NewsPushConfig cfg = new NewsPushConfig(
                true,
                List.of(feedUrl),
                3,
                "0 0 9 * * *",
                "UTC",
                "",
                "conv-1",
                "sender-1",
                400
        );
        NewsPushService service = new NewsPushService(
                HttpClient.newHttpClient(),
                delivery,
                new FailingLlmClient(),
                Clock.fixed(Instant.parse("2024-01-01T08:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5),
                cfg
        );
        NewsPushResult result = service.pushOnce();

        assertTrue(result.delivered());
        assertFalse(result.summary().isBlank());
        assertTrue(delivery.lastMessage.contains("Title A"));
    }

    private String sampleRss() {
        return """
                <rss version="2.0">
                  <channel>
                    <title>Sample Feed</title>
                    <item>
                      <title>Title A</title>
                      <link>http://example.com/a</link>
                      <description>Summary A</description>
                      <pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate>
                    </item>
                    <item>
                      <title>Title B</title>
                      <link>http://example.com/b</link>
                      <description>Summary B</description>
                      <pubDate>Mon, 01 Jan 2024 01:00:00 GMT</pubDate>
                    </item>
                  </channel>
                </rss>
                """;
    }

    private static final class RecordingDelivery implements NewsDeliveryClient {
        private final boolean returnValue;
        String lastMessage = "";

        RecordingDelivery(boolean returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public boolean deliver(String message, NewsPushConfig config) {
            this.lastMessage = message;
            return returnValue;
        }
    }

    private static final class FixedResponseHandler implements HttpHandler {
        private final String body;

        FixedResponseHandler(String body) {
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) {
            try {
                byte[] bytes = body.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        }
    }

    private static final class FixedLlmClient implements LlmClient {
        private final String reply;

        FixedLlmClient(String reply) {
            this.reply = reply;
        }

        @Override
        public String generateResponse(String prompt, java.util.Map<String, Object> context) {
            return reply;
        }
    }

    private static final class FailingLlmClient implements LlmClient {
        @Override
        public String generateResponse(String prompt, java.util.Map<String, Object> context) {
            throw new RuntimeException("llm down");
        }
    }
}

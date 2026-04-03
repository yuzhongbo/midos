package com.zhongbo.mindos.assistant.skill.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.CookieHandler;
import java.net.Authenticator;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BraveSearchRestClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldForwardSupportedWebSearchParametersAndRenderResults() throws Exception {
        Map<String, List<String>> captured = new LinkedHashMap<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (String pair : rawQuery.split("&")) {
                    String[] pieces = pair.split("=", 2);
                    String key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8);
                    String value = pieces.length > 1 ? URLDecoder.decode(pieces[1], StandardCharsets.UTF_8) : "";
                    captured.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                }
            }
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "web", Map.of("results", List.of(
                            Map.of(
                                    "title", "AI chip headline",
                                    "description", "New semiconductor update",
                                    "url", "https://example.com/ai-chip"
                            )
                    ))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BraveSearchRestClient client = new BraveSearchRestClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper
        );
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search";
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", "artificial intelligence");
        arguments.put("country", "US");
        arguments.put("search_lang", "en");
        arguments.put("ui_lang", "en-US");
        arguments.put("count", 5);
        arguments.put("offset", 1);
        arguments.put("safesearch", "moderate");
        arguments.put("freshness", "pw");
        arguments.put("text_decorations", true);
        arguments.put("spellcheck", true);
        arguments.put("result_filter", List.of("web", "query"));
        arguments.put("goggles", Arrays.asList("https://example.com/g1", "https://example.com/g2"));
        arguments.put("units", "metric");
        arguments.put("summary", true);

        String result = client.callTool(baseUrl, "brave_web_search", arguments, Map.of("X-Subscription-Token", "token"));

        assertEquals(List.of("artificial intelligence"), captured.get("q"));
        assertEquals(List.of("US"), captured.get("country"));
        assertEquals(List.of("en"), captured.get("search_lang"));
        assertEquals(List.of("en-US"), captured.get("ui_lang"));
        assertEquals(List.of("5"), captured.get("count"));
        assertEquals(List.of("1"), captured.get("offset"));
        assertEquals(List.of("moderate"), captured.get("safesearch"));
        assertEquals(List.of("pw"), captured.get("freshness"));
        assertEquals(List.of("true"), captured.get("text_decorations"));
        assertEquals(List.of("true"), captured.get("spellcheck"));
        assertEquals(List.of("web", "query"), captured.get("result_filter"));
        assertEquals(List.of("https://example.com/g1", "https://example.com/g2"), captured.get("goggles"));
        assertEquals(List.of("metric"), captured.get("units"));
        assertEquals(List.of("true"), captured.get("summary"));
        assertTrue(result.contains("AI chip headline"));
        assertTrue(result.contains("https://example.com/ai-chip"));
    }

    @Test
    void shouldLogSafeBraveRequestUrlAndParamsWithoutTokenValue() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "web", Map.of("results", List.of(
                            Map.of(
                                    "title", "AI log headline with a very long suffix for truncation verification in runtime debug logs",
                                    "description", "Debug entry",
                                    "url", "https://example.com/log"
                            ),
                            Map.of("title", "Second headline", "description", "Secondary entry", "url", "https://example.com/log2")
                    ))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Logger logger = Logger.getLogger(BraveSearchRestClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            BraveSearchRestClient client = new BraveSearchRestClient(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                    objectMapper
            );
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search";
            client.callTool(
                    baseUrl,
                    "brave_web_search",
                    Map.of("query", "artificial intelligence", "country", "US", "summary", true),
                    Map.of("X-Subscription-Token", "super-secret-token")
            );

            String logs = String.join("\n", handler.messages);
            assertTrue(logs.contains("brave.search.request"));
            assertTrue(logs.contains("brave.search.response"));
            assertTrue(logs.contains(baseUrl + "?q=artificial+intelligence"));
            assertTrue(logs.contains("country=US"));
            assertTrue(logs.contains("summary=true"));
            assertTrue(logs.contains("\"headerNames\":[X-Subscription-Token]"));
            assertTrue(logs.contains("\"phase\":\"success\""));
            assertTrue(logs.contains("\"status\":200"));
            assertTrue(logs.contains("\"resultCount\":2"));
            assertTrue(logs.contains("\"firstTitle\":\"AI log headline with a very long suffix for truncation verification"));
            assertTrue(logs.contains("…\",\"errorPreview\":\""));
            assertTrue(!logs.contains("super-secret-token"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldInferCountAndStripTrailingQuantityFromChineseNewsQuery() throws Exception {
        Map<String, List<String>> captured = new LinkedHashMap<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null && !rawQuery.isBlank()) {
                for (String pair : rawQuery.split("&")) {
                    String[] pieces = pair.split("=", 2);
                    String key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8);
                    String value = pieces.length > 1 ? URLDecoder.decode(pieces[1], StandardCharsets.UTF_8) : "";
                    captured.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                }
            }
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "web", Map.of("results", List.of(
                            Map.of("title", "国际快讯 1", "description", "摘要", "url", "https://example.com/world-1")
                    ))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BraveSearchRestClient client = new BraveSearchRestClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper
        );
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search";

        client.callTool(baseUrl, "webSearch", Map.of("query", "国际实时新闻 前五条"), Map.of("X-Subscription-Token", "token"));

        assertEquals(List.of("国际实时新闻"), captured.get("q"));
        assertEquals(List.of("5"), captured.get("count"));
    }

    @Test
    void shouldNormalizeDuplicatePathSlashesBeforeCallingBraveEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/brave/res/v1/web/search", exchange -> {
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "web", Map.of("results", List.of(
                            Map.of("title", "国际快讯", "description", "摘要", "url", "https://example.com/world")
                    ))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Logger logger = Logger.getLogger(BraveSearchRestClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            BraveSearchRestClient client = new BraveSearchRestClient(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                    objectMapper
            );
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "//brave/res/v1/web/search";

            String result = client.callTool(baseUrl, "webSearch", Map.of("query", "国际实时新闻 前五条"), Map.of("X-Subscription-Token", "token"));

            assertTrue(result.contains("国际快讯"));
            String logs = String.join("\n", handler.messages);
            assertTrue(logs.contains("/brave/res/v1/web/search?q=%E5%9B%BD%E9%99%85%E5%AE%9E%E6%97%B6%E6%96%B0%E9%97%BB&count=5"));
            assertTrue(!logs.contains("//brave/res/v1/web/search?q="));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldLogResponseSummaryWhenBraveReturnsNon2xx() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            byte[] response = "{\"error\":\"rate_limited\",\"message\":\"too many requests\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        Logger logger = Logger.getLogger(BraveSearchRestClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            BraveSearchRestClient client = new BraveSearchRestClient(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                    objectMapper
            );
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search";
            try {
                client.callTool(baseUrl, "webSearch", Map.of("query", "国际实时新闻"), Map.of("X-Subscription-Token", "token"));
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("HTTP 429"));
                assertTrue(expected.getMessage().contains("too many requests"));
            }

            String logs = String.join("\n", handler.messages);
            assertTrue(logs.contains("brave.search.response"));
            assertTrue(logs.contains("\"phase\":\"http_error\""));
            assertTrue(logs.contains("\"status\":429"));
            assertTrue(logs.contains("\"resultCount\":0"));
            assertTrue(logs.contains("too many requests"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldSanitizeCloudflareHtmlWhenBraveReturns403() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            byte[] response = "<!DOCTYPE html><html lang=\"en-US\"><head><title>Just a moment...</title></head><body>Cloudflare</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BraveSearchRestClient client = new BraveSearchRestClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper
        );
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/res/v1/web/search";

        try {
            client.callTool(baseUrl, "webSearch", Map.of("query", "国际实时新闻"), Map.of("X-Subscription-Token", "token"));
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("HTTP 403"));
            assertTrue(expected.getMessage().contains("upstream challenge"));
            assertTrue(!expected.getMessage().contains("<!DOCTYPE html>"));
        }
    }

    @Test
    void shouldLogResponseSummaryWhenTransportFailsBeforeAnyHttpResponse() {
        Logger logger = Logger.getLogger(BraveSearchRestClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            BraveSearchRestClient client = new BraveSearchRestClient(
                    new FailingHttpClient(new java.io.IOException("connect timed out")),
                    objectMapper
            );

            try {
                client.callTool(
                        "https://api.search.brave.com/res/v1/web/search",
                        "webSearch",
                        Map.of("query", "国际实时新闻", "count", 5),
                        Map.of("X-Subscription-Token", "token")
                );
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Failed to call Brave search"));
                assertTrue(expected.getMessage().contains("connect timed out"));
            }

            String logs = String.join("\n", handler.messages);
            assertTrue(logs.contains("brave.search.request"));
            assertTrue(logs.contains("brave.search.response"));
            assertTrue(logs.contains("\"phase\":\"transport_error\""));
            assertTrue(logs.contains("\"status\":0"));
            assertTrue(logs.contains("connect timed out"));
            assertTrue(logs.contains("https://api.search.brave.com/res/v1/web/search?q=%E5%9B%BD%E9%99%85%E5%AE%9E%E6%97%B6%E6%96%B0%E9%97%BB&count=5"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldRetryConnectionResetAndEventuallySucceed() {
        Logger logger = Logger.getLogger(BraveSearchRestClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            BraveSearchRestClient client = new BraveSearchRestClient(
                    new FlakyHttpClient(
                            new java.io.IOException("Connection reset"),
                            okResponse(200, "{\"web\":{\"results\":[{\"title\":\"Recovered result\",\"description\":\"Retry success\",\"url\":\"https://example.com/recovered\"}]}}")
                    ),
                    objectMapper
            );

            String result = client.callTool(
                    "https://api.search.brave.com/res/v1/web/search",
                    "webSearch",
                    Map.of("query", "国际实时新闻"),
                    Map.of("X-Subscription-Token", "token")
            );

            assertTrue(result.contains("Recovered result"));
            String logs = String.join("\n", handler.messages);
            assertTrue(logs.contains("brave.search.retry"));
            assertTrue(logs.contains("Connection reset"));
            assertTrue(logs.contains("\"phase\":\"success\""));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    private HttpResponse<String> okResponse(int statusCode, String body) {
        return new StubHttpResponse(statusCode, body);
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
    }

    private static final class FailingHttpClient extends HttpClient {
        private final java.io.IOException failure;

        private FailingHttpClient(java.io.IOException failure) {
            this.failure = failure;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws java.io.IOException {
            throw failure;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used in tests"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used in tests"));
        }
    }

    private static final class FlakyHttpClient extends HttpClient {
        private final List<Object> outcomes;
        private int index;

        private FlakyHttpClient(Object... outcomes) {
            this.outcomes = Arrays.asList(outcomes);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws java.io.IOException {
            Object outcome = outcomes.get(Math.min(index++, outcomes.size() - 1));
            if (outcome instanceof java.io.IOException ioException) {
                throw ioException;
            }
            return (HttpResponse<T>) outcome;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used in tests"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used in tests"));
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
            return HttpRequest.newBuilder(URI.create("https://example.com")).build();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.com");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}


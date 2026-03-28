package com.zhongbo.mindos.assistant.llm;

import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.common.ImDegradedReplyMarker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyLlmClientTest {

    @Test
    void shouldCallHttpAndReturnAssistantContentWhenHttpEnabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"hello from proxy\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "gemini",
                    "fixed",
                    "llm-dsl:gemini,llm-fallback:gemini",
                    "cost:gemini,quality:gemini",
                    "gemini:key-gemini",
                    false,
                    60,
                    true,
                    "gemini:http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"
            );

            String output = client.generateResponse("hello", Map.of("userId", "u-http", "llmProvider", "gemini"));

            assertEquals("hello from proxy", output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnErrorWhenHttpStatusIsNon2xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "upstream boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "openai",
                    "fixed",
                    "llm-dsl:openai,llm-fallback:openai",
                    "cost:openai,quality:openai",
                    "openai:key-openai",
                    false,
                    60,
                    true,
                    "openai:http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"
            );

            String output = client.generateResponse("hello", Map.of("userId", "u-http", "llmProvider", "openai"));

            assertEquals("[LLM openai] request failed after 1 attempt(s). Please retry later.", output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnFriendlyFallbackForImWhenHttpStatusIsNon2xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "upstream boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "openai",
                    "fixed",
                    "llm-dsl:openai,llm-fallback:openai",
                    "cost:openai,quality:openai",
                    "openai:key-openai",
                    false,
                    60,
                    true,
                    "openai:http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"
            );

            String output = client.generateResponse("hello", Map.of(
                    "userId", "u-http",
                    "llmProvider", "openai",
                    "interactionChannel", "im",
                    "imPlatform", "dingtalk"
            ));

            assertEquals(ImDegradedReplyMarker.encode("openai", "upstream_5xx"), output);
            assertFalse(output.contains("hello"));
            assertFalse(output.contains("upstream boom"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCallNativeGeminiEndpointWithQueryKeyAndNativePayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("query", exchange.getRequestURI().getQuery());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            observed.put("auth", authorization == null ? "" : authorization);
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello from gemini native\"}]}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "gemini",
                    "fixed",
                    "llm-dsl:gemini,llm-fallback:gemini",
                    "cost:gemini,quality:gemini",
                    "gemini:key-gemini",
                    false,
                    60,
                    true,
                    "gemini:http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/gemini-2.0-flash:generateContent"
            );

            var resolveEndpoint = ApiKeyLlmClient.class.getDeclaredMethod("resolveEndpoint", String.class);
            resolveEndpoint.setAccessible(true);
            String resolvedEndpoint = (String) resolveEndpoint.invoke(client, "gemini");
            assertTrue(resolvedEndpoint.contains(":generateContent"));

            var isNativeGeminiEndpoint = ApiKeyLlmClient.class.getDeclaredMethod("isNativeGeminiEndpoint", String.class);
            isNativeGeminiEndpoint.setAccessible(true);
            assertTrue((boolean) isNativeGeminiEndpoint.invoke(client, resolvedEndpoint));

            var callNativeGeminiProvider = ApiKeyLlmClient.class.getDeclaredMethod(
                    "callNativeGeminiProvider",
                    String.class,
                    String.class,
                    String.class,
                    Map.class,
                    String.class
            );
            callNativeGeminiProvider.setAccessible(true);
            String directOutput = (String) callNativeGeminiProvider.invoke(
                    client,
                    "gemini",
                    resolvedEndpoint,
                    "hello native",
                    Map.of("temperature", 0.2, "maxTokens", 128),
                    "key-gemini"
            );
            assertEquals("hello from gemini native", directOutput);

            String output = client.generateResponse("hello native", Map.of(
                    "userId", "u-gemini",
                    "llmProvider", "gemini",
                    "temperature", 0.2,
                    "maxTokens", 128
            ));

            assertEquals("hello from gemini native", output);
            assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", observed.get("path"));
            assertEquals("key=key-gemini", observed.get("query"));
            assertEquals("", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"contents\""));
            assertTrue(observed.get("body").contains("hello native"));
            assertTrue(observed.get("body").contains("\"maxOutputTokens\":128"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepExistingGeminiQueryKeyAndMaskItInMetrics() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("query", exchange.getRequestURI().getQuery());
            byte[] body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "gemini",
                    "fixed",
                    "llm-dsl:gemini,llm-fallback:gemini",
                    "cost:gemini,quality:gemini",
                    "gemini:key-from-map",
                    false,
                    60,
                    true,
                    "gemini:http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/gemini-2.0-flash:generateContent?key=config-key"
            );

            String output = client.generateResponse("hello", Map.of("userId", "u-gemini-2", "llmProvider", "gemini"));

            assertEquals("ok", output);
            assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", observed.get("path"));
            assertEquals("key=config-key", observed.get("query"));
            List<?> calls = readRecordedCalls(client);
            assertFalse(calls.isEmpty());
            Object lastCall = calls.get(calls.size() - 1);
            var endpointAccessor = lastCall.getClass().getDeclaredMethod("endpoint");
            endpointAccessor.setAccessible(true);
            String metricEndpoint = (String) endpointAccessor.invoke(lastCall);
            assertTrue(metricEndpoint.contains("key=***"));
            assertFalse(metricEndpoint.contains("config-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseSkeletonWhenHttpDisabled() {
        ApiKeyLlmClient client = newClient(
                "gemini",
                "fixed",
                "llm-dsl:gemini,llm-fallback:gemini",
                "cost:gemini,quality:gemini",
                "gemini:key-gemini",
                false,
                60,
                false,
                "gemini:http://127.0.0.1:65534/v1/chat/completions"
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-http", "llmProvider", "gemini"));

        assertTrue(output.startsWith("[LLM gemini] fallback mode active."));
        assertFalse(output.contains("hello"));
    }

    @Test
    void shouldUseBuiltInMainlandEndpointWhenProviderEndpointNotConfigured() {
        ApiKeyLlmClient client = newClient(
                "deepseek",
                "fixed",
                "llm-dsl:deepseek,llm-fallback:deepseek",
                "cost:deepseek,quality:deepseek",
                "deepseek:key-deepseek",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-cn", "llmProvider", "deepseek"));

        assertTrue(output.contains("https://api.deepseek.com/v1/chat/completions"));
        assertTrue(output.startsWith("[LLM deepseek] fallback mode active."));
        assertFalse(output.contains("hello"));
    }

    @Test
    void shouldResolveProviderKeyByMainlandAlias() {
        ApiKeyLlmClient client = newClient(
                "qwen",
                "fixed",
                "llm-dsl:qwen,llm-fallback:qwen",
                "cost:qwen,quality:qwen",
                "dashscope:key-qwen",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-cn", "llmProvider", "tongyi"));

        assertTrue(output.contains("apiKey=key***en"));
        assertTrue(output.startsWith("[LLM qwen] fallback mode active."));
        assertTrue(output.contains("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        assertFalse(output.contains("hello"));
    }

    @Test
    void shouldDefaultQwenModelToQwen35PlusWhenModelNotProvided() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"qwen ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "qwen",
                    "fixed",
                    "llm-dsl:qwen,llm-fallback:qwen",
                    "cost:qwen,quality:qwen",
                    "qwen:key-qwen",
                    false,
                    60,
                    true,
                    "qwen:http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1/chat/completions"
            );

            String output = client.generateResponse("你是谁？", Map.of("userId", "u-qwen", "llmProvider", "qwen"));

            assertEquals("qwen ok", output);
            assertTrue(observed.get("body").contains("\"model\":\"qwen3.5-plus\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRespectExplicitQwenModelOverride() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"qwen override ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "qwen",
                    "fixed",
                    "llm-dsl:qwen,llm-fallback:qwen",
                    "cost:qwen,quality:qwen",
                    "qwen:key-qwen",
                    false,
                    60,
                    true,
                    "qwen:http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1/chat/completions"
            );

            String output = client.generateResponse("你是谁？", Map.of(
                    "userId", "u-qwen-override",
                    "llmProvider", "qwen",
                    "model", "qwen-max-latest"
            ));

            assertEquals("qwen override ok", output);
            assertTrue(observed.get("body").contains("\"model\":\"qwen-max-latest\""));
            assertFalse(observed.get("body").contains("\"model\":\"qwen3.5-plus\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRespectExplicitProviderOverrideInFixedMode() {
        ApiKeyLlmClient client = newClient(
                "stub",
                "fixed",
                "llm-dsl:openai,llm-fallback:local",
                "cost:local,quality:openai",
                "openai:key-openai,local:key-local",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u1", "llmProvider", "local", "routeStage", "llm-dsl"));

        assertTrue(output.startsWith("[LLM local] fallback mode active."));
    }

    @Test
    void shouldRouteByStageWhenAutoModeEnabled() {
        ApiKeyLlmClient client = newClient(
                "stub",
                "auto",
                "llm-dsl:openai,llm-fallback:local",
                "cost:local,quality:openai",
                "openai:key-openai,local:key-local",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u1", "llmProvider", "auto", "routeStage", "llm-fallback"));

        assertTrue(output.startsWith("[LLM local] fallback mode active."));
    }

    @Test
    void shouldFallbackToDefaultProviderWhenStageIsUnknown() {
        ApiKeyLlmClient client = newClient(
                "openai",
                "auto",
                "llm-dsl:openai,llm-fallback:local",
                "cost:local,quality:openai",
                "openai:key-openai,local:key-local",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u1", "llmProvider", "auto", "routeStage", "custom-stage"));

        assertTrue(output.startsWith("[LLM openai] fallback mode active."));
    }

    @Test
    void shouldRouteByPresetWhenProvided() {
        ApiKeyLlmClient client = newClient(
                "openai",
                "auto",
                "llm-dsl:openai,llm-fallback:openai",
                "cost:local,quality:openai",
                "openai:key-openai,local:key-local",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u1", "llmPreset", "cost", "routeStage", "llm-fallback"));

        assertTrue(output.startsWith("[LLM local] fallback mode active."));
    }

    @Test
    void shouldFallbackToStageRouteWhenPresetIsUnknown() {
        ApiKeyLlmClient client = newClient(
                "openai",
                "auto",
                "llm-dsl:openai,llm-fallback:local",
                "cost:local,quality:openai",
                "openai:key-openai,local:key-local",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u1", "llmPreset", "unknown", "llmProvider", "auto", "routeStage", "llm-fallback"));

        assertTrue(output.startsWith("[LLM local] fallback mode active."));
    }

    @Test
    void shouldReturnFriendlyFallbackForImWhenApiKeyMissing() {
        ApiKeyLlmClient client = newClient(
                "stub",
                "fixed",
                "llm-dsl:openai,llm-fallback:openai",
                "cost:openai,quality:openai",
                "",
                false,
                60,
                "",
                false,
                ""
        );

        String output = client.generateResponse("hello", Map.of(
                "userId", "u-im",
                "interactionChannel", "im",
                "imPlatform", "feishu"
        ));

        assertEquals(ImDegradedReplyMarker.encode("stub", "auth_failure"), output);
        assertFalse(output.contains("hello"));
    }

    @Test
    void shouldCacheShortTtlResponsesWhenEnabled() throws Exception {
        ApiKeyLlmClient client = newClient(
                "openai",
                "fixed",
                "llm-dsl:openai,llm-fallback:openai",
                "cost:openai,quality:openai",
                "openai:key-openai",
                true,
                60
        );

        client.generateResponse("same prompt", Map.of("userId", "u-cache", "routeStage", "llm-dsl"));
        client.generateResponse("same prompt", Map.of("userId", "u-cache", "routeStage", "llm-dsl"));

        assertEquals(1, readCacheSize(client));
        assertEquals(0.5, client.snapshotCacheMetrics().hitRate());
        assertEquals(1, client.snapshotCacheMetrics().hitCount());
        assertEquals(0.5, client.snapshotWindowCacheHitRate(60));
        assertEquals(1, client.snapshotWindowCacheMetrics(60).hits());
        assertEquals(1, client.snapshotWindowCacheMetrics(60).misses());
    }

    @Test
    void shouldExpireCachedResponsesAfterTtl() throws Exception {
        ApiKeyLlmClient client = newClient(
                "openai",
                "fixed",
                "llm-dsl:openai,llm-fallback:openai",
                "cost:openai,quality:openai",
                "openai:key-openai",
                true,
                1
        );

        client.generateResponse("same prompt", Map.of("userId", "u-cache", "routeStage", "llm-dsl"));
        long createdAtFirst = readSingleCacheCreatedAt(client);
        Thread.sleep(1_150L);
        client.generateResponse("same prompt", Map.of("userId", "u-cache", "routeStage", "llm-dsl"));
        long createdAtSecond = readSingleCacheCreatedAt(client);

        assertEquals(1, readCacheSize(client));
        assertTrue(createdAtSecond > createdAtFirst);
    }

    @SuppressWarnings("unchecked")
    private int readCacheSize(ApiKeyLlmClient client) throws Exception {
        var field = ApiKeyLlmClient.class.getDeclaredField("responseCache");
        field.setAccessible(true);
        return ((ConcurrentHashMap<String, ?>) field.get(client)).size();
    }

    @SuppressWarnings("unchecked")
    private long readSingleCacheCreatedAt(ApiKeyLlmClient client) throws Exception {
        var field = ApiKeyLlmClient.class.getDeclaredField("responseCache");
        field.setAccessible(true);
        ConcurrentHashMap<String, Object> cache = (ConcurrentHashMap<String, Object>) field.get(client);
        Object value = cache.values().iterator().next();
        var accessor = value.getClass().getDeclaredMethod("createdAtEpochMillis");
        accessor.setAccessible(true);
        return (long) accessor.invoke(value);
    }

    @SuppressWarnings("unchecked")
    private List<Object> readRecordedCalls(ApiKeyLlmClient client) throws Exception {
        var metricsField = ApiKeyLlmClient.class.getDeclaredField("llmMetricsService");
        metricsField.setAccessible(true);
        Object metricsService = metricsField.get(client);
        var callsField = metricsService.getClass().getDeclaredField("calls");
        callsField.setAccessible(true);
        return List.copyOf((java.util.concurrent.ConcurrentLinkedDeque<Object>) callsField.get(metricsService));
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds) {
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, cacheEnabled, cacheTtlSeconds,
                false, "");
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      boolean httpEnabled,
                                      String providerEndpoints) {
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, cacheEnabled, cacheTtlSeconds,
                "fallback-global-key", httpEnabled, providerEndpoints);
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      String globalApiKey,
                                      boolean httpEnabled,
                                      String providerEndpoints) {
        ObjectProvider<UserApiKeyRepository> provider = new DefaultListableBeanFactory().getBeanProvider(UserApiKeyRepository.class);
        UserApiKeyService userApiKeyService = new UserApiKeyService(provider, new AesApiKeyCryptoService(""));
        LlmMetricsService metricsService = new LlmMetricsService(true, 200);
        return new ApiKeyLlmClient(
                defaultProvider,
                routingMode,
                "https://api.example.com/v1/chat/completions",
                globalApiKey,
                providerEndpoints,
                providerKeys,
                stageMap,
                presetMap,
                "",
                1,
                0L,
                httpEnabled,
                cacheEnabled,
                cacheTtlSeconds,
                256,
                userApiKeyService,
                metricsService
        );
    }
}


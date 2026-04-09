package com.zhongbo.mindos.assistant.llm;

import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.common.ImDegradedReplyMarker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyLlmClientTest {

    @AfterEach
    void cleanupLoggerHandlers() {
        Logger logger = Logger.getLogger(ApiKeyLlmClient.class.getName());
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof CapturingHandler) {
                logger.removeHandler(handler);
            }
        }
    }

    @Test
    void shouldCallHttpAndReturnAssistantContentWhenHttpEnabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/v1/chat/completions", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"hello from \"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"proxy\"}}]}\n\n"
                    + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
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
            assertTrue(observed.get("body").contains("\"stream\":true"));
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
            byte[] body = ("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello from gemini \"}]}}]}\n\n"
                    + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"native\"}]}}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
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


            String output = client.generateResponse("hello native", Map.of(
                    "userId", "u-gemini",
                    "llmProvider", "gemini",
                    "temperature", 0.2,
                    "maxTokens", 128
            ));

            assertEquals("hello from gemini native", output);
            assertEquals("/v1beta/models/gemini-2.0-flash:streamGenerateContent", observed.get("path"));
            assertEquals("key=key-gemini&alt=sse", observed.get("query"));
            assertEquals("", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"contents\""));
            assertTrue(observed.get("body").contains("hello native"));
            assertTrue(observed.get("body").contains("\"maxOutputTokens\":128"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCallOllamaGenerateEndpointWithoutApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/generate", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (
                    "{\"model\":\"gemma3:1b-it-q4_K_M\",\"response\":\"本地\",\"done\":false}\n"
                            + "{\"model\":\"gemma3:1b-it-q4_K_M\",\"response\":\"优先\",\"done\":true}\n"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "local",
                    "auto",
                    "llm-dsl:local,llm-fallback:qwen",
                    "cost:local,quality:qwen",
                    "qwen:key-qwen",
                    "local:gemma3:1b-it-q4_K_M",
                    false,
                    60,
                    "",
                    true,
                    "local:http://127.0.0.1:" + server.getAddress().getPort() + "/api/generate,qwen:https://api.example.com/v1/chat/completions"
            );

            String output = client.generateResponse("做个简短总结", Map.of(
                    "userId", "u-ollama",
                    "llmProvider", "local",
                    "temperature", 0.1,
                    "maxTokens", 64
            ));

            assertEquals("本地优先", output);
            assertEquals("/api/generate", observed.get("path"));
            assertEquals("null", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"model\":\"gemma3:1b-it-q4_K_M\""));
            assertTrue(observed.get("body").contains("\"prompt\":\"做个简短总结\""));
            assertTrue(observed.get("body").contains("\"num_predict\":64"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCallOllamaChatEndpointWithoutApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/chat", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (
                    "{\"model\":\"gemma3:1b-it-q4_K_M\",\"message\":{\"role\":\"assistant\",\"content\":\"本地\"},\"done\":false}\n"
                            + "{\"model\":\"gemma3:1b-it-q4_K_M\",\"message\":{\"role\":\"assistant\",\"content\":\"对话\"},\"done\":true}\n"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "local",
                    "auto",
                    "llm-dsl:local,llm-fallback:qwen",
                    "cost:local,quality:qwen",
                    "qwen:key-qwen",
                    "local:gemma3:1b-it-q4_K_M",
                    false,
                    60,
                    "",
                    true,
                    "local:http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat,qwen:https://api.example.com/v1/chat/completions"
            );

            String output = client.generateResponse("做个简短总结", Map.of(
                    "userId", "u-ollama-chat",
                    "llmProvider", "local",
                    "temperature", 0.1,
                    "maxTokens", 64
            ));

            assertEquals("本地对话", output);
            assertEquals("/api/chat", observed.get("path"));
            assertEquals("null", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"model\":\"gemma3:1b-it-q4_K_M\""));
            assertTrue(observed.get("body").contains("\"messages\""));
            assertTrue(observed.get("body").contains("\"role\":\"user\""));
            assertTrue(observed.get("body").contains("\"num_predict\":64"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldStreamOllamaChatChunksWithoutWaitingForWholeBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            var output = exchange.getResponseBody();
            output.write("{\"message\":{\"content\":\"本地\"},\"done\":false}\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            try {
                Thread.sleep(350L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            output.write("{\"message\":{\"content\":\"流式\"},\"done\":true}\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "local",
                    "auto",
                    "llm-dsl:local,llm-fallback:qwen",
                    "cost:local,quality:qwen",
                    "qwen:key-qwen",
                    "local:gemma3:1b-it-q4_K_M",
                    false,
                    60,
                    "",
                    true,
                    "local:http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat,qwen:https://api.example.com/v1/chat/completions"
            );

            List<String> deltas = new CopyOnWriteArrayList<>();
            AtomicLong firstDeltaElapsedMs = new AtomicLong(-1L);
            long startedAt = System.currentTimeMillis();

            client.streamResponse("做个简短总结", Map.of(
                    "userId", "u-ollama-stream",
                    "llmProvider", "local"
            ), chunk -> {
                deltas.add(chunk);
                firstDeltaElapsedMs.compareAndSet(-1L, System.currentTimeMillis() - startedAt);
            });

            assertEquals("本地流式", String.join("", deltas));
            assertTrue(firstDeltaElapsedMs.get() >= 0, "first delta should be emitted");
            assertTrue(firstDeltaElapsedMs.get() < 300L,
                    "first delta should arrive before second chunk delay, actual=" + firstDeltaElapsedMs.get());
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
            assertEquals("/v1beta/models/gemini-2.0-flash:streamGenerateContent", observed.get("path"));
            assertEquals("key=config-key&alt=sse", observed.get("query"));
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
    void shouldUseBuiltInOpenRouterEndpointWhenConfiguredAsProvider() {
        ApiKeyLlmClient client = newClient(
                "openrouter",
                "fixed",
                "llm-dsl:openrouter,llm-fallback:openrouter",
                "cost:openrouter,quality:openrouter",
                "openrouter:key-openrouter",
                "openrouter:openai/gpt-4o-mini",
                false,
                60,
                false,
                ""
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-openrouter", "llmProvider", "openrouter"));

        assertTrue(output.contains("https://openrouter.ai/api/v1/chat/completions"));
        assertTrue(output.startsWith("[LLM openrouter] fallback mode active."));
        assertFalse(output.contains("hello"));
    }

    @Test
    void shouldRequireExplicitModelForOpenRouter() {
        ApiKeyLlmClient client = newClient(
                "openrouter",
                "fixed",
                "llm-dsl:openrouter,llm-fallback:openrouter",
                "cost:openrouter,quality:openrouter",
                "openrouter:key-openrouter",
                false,
                60
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-openrouter", "llmProvider", "openrouter"));

        assertEquals("[LLM openrouter] unavailable: missing model. Configure mindos.llm.provider-models=openrouter:<provider/model> or pass context.model.", output);
    }

    @Test
    void shouldPreferStableProviderFromLlmProfile() {
        ApiKeyLlmClient client = newClientWithProfile(
                "gpt",
                "QWEN_STABLE",
                "fixed",
                "llm-dsl:gpt,llm-fallback:gpt",
                "cost:gpt,quality:gpt",
                "qwen:key-qwen,gpt:key-gpt",
                false,
                60,
                false,
                "gpt:https://openrouter.ai/api/v1/chat/completions,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-profile"));

        assertTrue(output.startsWith("[LLM qwen] fallback mode active."));
    }

    @Test
    void shouldPreferLocalProviderFromCustomLocalFirstProfile() {
        ApiKeyLlmClient client = newClientWithProfile(
                "qwen",
                "CUSTOM_LOCAL_FIRST",
                "fixed",
                "llm-dsl:qwen,llm-fallback:qwen",
                "cost:qwen,quality:qwen",
                "qwen:key-qwen,local:key-local",
                false,
                60,
                false,
                "local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        );

        String output = client.generateResponse("hello", Map.of("userId", "u-local-profile"));

        assertTrue(output.startsWith("[LLM local] fallback mode active."));
        assertTrue(output.contains("http://localhost:11434/api/chat"));
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
            assertTrue(observed.get("body").contains("\"model\":\"qwen3.6-plus\""));
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
            assertFalse(observed.get("body").contains("\"model\":\"qwen3.6-plus\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseConfiguredDoubaoModelAndBearerAuth() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/chat/completions", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"doubao \"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:ep-20260329-test",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/chat/completions"
            );

            String output = client.generateResponse("你好", Map.of("userId", "u-doubao", "llmProvider", "doubao"));

            assertEquals("doubao ok", output);
            assertEquals("/api/v3/chat/completions", observed.get("path"));
            assertEquals("Bearer ark-key", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"model\":\"ep-20260329-test\""));
            assertTrue(observed.get("body").contains("\"messages\""));
            assertTrue(observed.get("body").contains("\"stream\":true"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldResolveVolcengineAliasToDoubaoModelEndpointAndKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/chat/completions", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"alias ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "volcengine",
                    "fixed",
                    "llm-dsl:volcengine,llm-fallback:volcengine",
                    "cost:volcengine,quality:volcengine",
                    "volcengine:ark-key",
                    "volcengine:ep-20260329-alias",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/chat/completions"
            );

            String output = client.generateResponse("你好", Map.of("userId", "u-volcengine", "llmProvider", "volcengine"));

            assertEquals("alias ok", output);
            assertEquals("/api/v3/chat/completions", observed.get("path"));
            assertEquals("Bearer ark-key", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"model\":\"ep-20260329-alias\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCallArkResponsesEndpointWithWebSearchTool() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/responses", exchange -> {
            observed.put("path", exchange.getRequestURI().getPath());
            observed.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"今天热点：\"}\n\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"A、B、C\"}\n\n"
                            + "data: [DONE]\n\n"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:deepseek-v3-2-251201",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/responses"
            );

            String output = client.generateResponse("今天有什么热点新闻", Map.of("userId", "u-ark", "llmProvider", "doubao"));

            assertEquals("今天热点：A、B、C", output);
            assertEquals("/api/v3/responses", observed.get("path"));
            assertEquals("Bearer ark-key", observed.get("auth"));
            assertTrue(observed.get("body").contains("\"model\":\"deepseek-v3-2-251201\""));
            assertTrue(observed.get("body").contains("\"tools\""));
            assertTrue(observed.get("body").contains("\"type\":\"web_search\""));
            assertTrue(observed.get("body").contains("\"max_keyword\":3"));
            assertTrue(observed.get("body").contains("\"type\":\"input_text\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseArkResponsesJsonOutputWhenNotSse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v3/responses", exchange -> {
            byte[] body = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"新闻摘要\"}]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:deepseek-v3-2-251201",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/responses"
            );

            String output = client.generateResponse("今天有什么热点新闻", Map.of("userId", "u-ark-json", "llmProvider", "doubao"));

            assertEquals("新闻摘要", output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSkipArkWebSearchToolWhenDisabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/responses", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:deepseek-v3-2-251201",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/responses",
                    false,
                    "",
                    ""
            );

            String output = client.generateResponse("今天有什么热点新闻", Map.of("userId", "u-ark-disabled", "llmProvider", "doubao"));

            assertEquals("ok", output);
            assertFalse(observed.get("body").contains("\"tools\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldApplyArkWebSearchStageFilter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/responses", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:deepseek-v3-2-251201",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/responses",
                    true,
                    "llm-fallback",
                    ""
            );

            client.generateResponse("今天有什么热点新闻", Map.of(
                    "userId", "u-ark-stage",
                    "llmProvider", "doubao",
                    "routeStage", "llm-dsl"
            ));
            assertFalse(observed.get("body").contains("\"tools\""));

            client.generateResponse("今天有什么热点新闻", Map.of(
                    "userId", "u-ark-stage",
                    "llmProvider", "doubao",
                    "routeStage", "llm-fallback"
            ));
            assertTrue(observed.get("body").contains("\"tools\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldApplyArkWebSearchPlatformFilter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/responses", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:deepseek-v3-2-251201",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/responses",
                    true,
                    "",
                    "dingtalk"
            );

            client.generateResponse("今天有什么热点新闻", Map.of(
                    "userId", "u-ark-platform",
                    "llmProvider", "doubao",
                    "interactionChannel", "im",
                    "imPlatform", "feishu"
            ));
            assertFalse(observed.get("body").contains("\"tools\""));

            client.generateResponse("今天有什么热点新闻", Map.of(
                    "userId", "u-ark-platform",
                    "llmProvider", "doubao",
                    "interactionChannel", "im",
                    "imPlatform", "dingtalk"
            ));
            assertTrue(observed.get("body").contains("\"tools\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPreferExplicitDoubaoModelOverConfiguredProviderModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ConcurrentHashMap<String, String> observed = new ConcurrentHashMap<>();
        server.createContext("/api/v3/chat/completions", exchange -> {
            observed.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"doubao override ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    "doubao:ep-20260329-configured",
                    false,
                    60,
                    true,
                    "doubao:http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/chat/completions"
            );

            String output = client.generateResponse("你好", Map.of(
                    "userId", "u-doubao-override",
                    "llmProvider", "doubao",
                    "model", "ep-20260329-explicit"
            ));

            assertEquals("doubao override ok", output);
            assertTrue(observed.get("body").contains("\"model\":\"ep-20260329-explicit\""));
            assertFalse(observed.get("body").contains("\"model\":\"ep-20260329-configured\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnHelpfulMessageWhenDoubaoModelIsMissing() {
        ApiKeyLlmClient client = newClient(
                "doubao",
                "fixed",
                "llm-dsl:doubao,llm-fallback:doubao",
                "cost:doubao,quality:doubao",
                "doubao:ark-key",
                false,
                60,
                true,
                "doubao:https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        );

        String output = client.generateResponse("你好", Map.of("userId", "u-doubao-missing", "llmProvider", "doubao"));

        assertEquals("[LLM doubao] unavailable: missing Model ID / Endpoint ID. Configure mindos.llm.provider-models=doubao:<endpoint-or-model-id> or pass context.model.", output);
    }

    @Test
    void shouldIgnoreDoubaoTemplatePlaceholderUntilOperatorFillsIt() {
        ApiKeyLlmClient client = newClient(
                "doubao",
                "fixed",
                "llm-dsl:doubao,llm-fallback:doubao",
                "cost:doubao,quality:doubao",
                "doubao:ark-key",
                "doubao:REPLACE_WITH_YOUR_DOUBAO_ENDPOINT_ID",
                false,
                60,
                true,
                "doubao:https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        );

        String output = client.generateResponse("你好", Map.of("userId", "u-doubao-placeholder", "llmProvider", "doubao"));

        assertEquals("[LLM doubao] unavailable: missing Model ID / Endpoint ID. Configure mindos.llm.provider-models=doubao:<endpoint-or-model-id> or pass context.model.", output);
    }

    @Test
    void shouldLogDoubaoProfileMisconfigurationAtStartup() {
        Logger logger = Logger.getLogger(ApiKeyLlmClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            newClient(
                    "gpt",
                    "DOUBAO_STABLE",
                    "fixed",
                    "llm-dsl:gpt,llm-fallback:gpt",
                    "cost:gpt,quality:gpt",
                    "doubao:REPLACE_WITH_DOUBAO_ARK_KEY",
                    "doubao:REPLACE_WITH_DOUBAO_ENDPOINT_ID",
                    false,
                    60,
                    "fallback-global-key",
                    true,
                    "doubao:https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                    true,
                    "",
                    ""
            );
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"llm.routing.profile.misconfigured\""));
        assertTrue(logs.contains("\"llmProfile\":\"DOUBAO_STABLE\""));
        assertTrue(logs.contains("\"provider\":\"doubao\""));
    }

    @Test
    void shouldLogPrecheckFailureWhenDoubaoModelMissing() {
        Logger logger = Logger.getLogger(ApiKeyLlmClient.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            ApiKeyLlmClient client = newClient(
                    "doubao",
                    "fixed",
                    "llm-dsl:doubao,llm-fallback:doubao",
                    "cost:doubao,quality:doubao",
                    "doubao:ark-key",
                    false,
                    60,
                    true,
                    "doubao:https://ark.cn-beijing.volces.com/api/v3/chat/completions"
            );

            String output = client.generateResponse("你好", Map.of("userId", "u-doubao-missing-log", "llmProvider", "doubao"));
            assertTrue(output.contains("missing Model ID / Endpoint ID"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }

        String logs = handler.joinedMessages();
        assertTrue(logs.contains("\"event\":\"llm.call.precheck.failed\""));
        assertTrue(logs.contains("\"reason\":\"missing_model\""));
        assertTrue(logs.contains("\"provider\":\"doubao\""));
        assertTrue(logs.contains("\"providerConfigState\""));
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

    private ApiKeyLlmClient newClientWithProfile(String defaultProvider,
                                                 String llmProfile,
                                                 String routingMode,
                                                 String stageMap,
                                                 String presetMap,
                                                 String providerKeys,
                                                 boolean cacheEnabled,
                                                 long cacheTtlSeconds,
                                                 boolean httpEnabled,
                                                 String providerEndpoints) {
        return newClient(defaultProvider, llmProfile, routingMode, stageMap, presetMap, providerKeys, "", cacheEnabled,
                cacheTtlSeconds, "fallback-global-key", httpEnabled, providerEndpoints,
                true, "", "");
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
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, "", cacheEnabled, cacheTtlSeconds,
                httpEnabled, providerEndpoints);
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      String providerModels,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      boolean httpEnabled,
                                      String providerEndpoints) {
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, providerModels, cacheEnabled, cacheTtlSeconds,
                "fallback-global-key", httpEnabled, providerEndpoints);
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      String providerModels,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      boolean httpEnabled,
                                      String providerEndpoints,
                                      boolean arkWebSearchEnabled,
                                      String arkWebSearchStages,
                                      String arkWebSearchPlatforms) {
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, providerModels,
                cacheEnabled, cacheTtlSeconds, "fallback-global-key", httpEnabled, providerEndpoints,
                arkWebSearchEnabled, arkWebSearchStages, arkWebSearchPlatforms);
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
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, "", cacheEnabled, cacheTtlSeconds,
                globalApiKey, httpEnabled, providerEndpoints);
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      String providerModels,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      String globalApiKey,
                                      boolean httpEnabled,
                                      String providerEndpoints) {
        return newClient(defaultProvider, routingMode, stageMap, presetMap, providerKeys, providerModels,
                cacheEnabled, cacheTtlSeconds, globalApiKey, httpEnabled, providerEndpoints,
                true, "", "");
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String llmProfile,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      String providerModels,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      String globalApiKey,
                                      boolean httpEnabled,
                                      String providerEndpoints,
                                      boolean arkWebSearchEnabled,
                                      String arkWebSearchStages,
                                      String arkWebSearchPlatforms) {
        ObjectProvider<UserApiKeyRepository> provider = new DefaultListableBeanFactory().getBeanProvider(UserApiKeyRepository.class);
        UserApiKeyService userApiKeyService = new UserApiKeyService(provider, new AesApiKeyCryptoService(""));
        LlmMetricsService metricsService = new LlmMetricsService(true, 200);
        return new ApiKeyLlmClient(
                defaultProvider,
                llmProfile,
                routingMode,
                "https://api.example.com/v1/chat/completions",
                globalApiKey,
                providerEndpoints,
                providerModels,
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
                arkWebSearchEnabled,
                arkWebSearchStages,
                arkWebSearchPlatforms,
                userApiKeyService,
                metricsService
        );
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      String providerModels,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds,
                                      String globalApiKey,
                                      boolean httpEnabled,
                                      String providerEndpoints,
                                      boolean arkWebSearchEnabled,
                                      String arkWebSearchStages,
                                      String arkWebSearchPlatforms) {
        return newClient(defaultProvider, "", routingMode, stageMap, presetMap, providerKeys, providerModels,
                cacheEnabled, cacheTtlSeconds, globalApiKey, httpEnabled, providerEndpoints,
                arkWebSearchEnabled, arkWebSearchStages, arkWebSearchPlatforms);
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

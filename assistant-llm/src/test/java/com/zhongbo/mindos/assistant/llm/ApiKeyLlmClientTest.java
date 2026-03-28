package com.zhongbo.mindos.assistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyLlmClientTest {

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
        assertTrue(output.startsWith("[LLM deepseek]"));
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
        assertTrue(output.startsWith("[LLM qwen]"));
        assertTrue(output.contains("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
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

        assertTrue(output.startsWith("[LLM local]"));
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

        assertTrue(output.startsWith("[LLM local]"));
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

        assertTrue(output.startsWith("[LLM openai]"));
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

        assertTrue(output.startsWith("[LLM local]"));
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

        assertTrue(output.startsWith("[LLM local]"));
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

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys,
                                      boolean cacheEnabled,
                                      long cacheTtlSeconds) {
        ObjectProvider<UserApiKeyRepository> provider = new DefaultListableBeanFactory().getBeanProvider(UserApiKeyRepository.class);
        UserApiKeyService userApiKeyService = new UserApiKeyService(provider, new AesApiKeyCryptoService(""));
        LlmMetricsService metricsService = new LlmMetricsService(true, 200);
        return new ApiKeyLlmClient(
                defaultProvider,
                routingMode,
                "https://api.example.com/v1/chat/completions",
                "fallback-global-key",
                "",
                providerKeys,
                stageMap,
                presetMap,
                "",
                1,
                0L,
                false,
                cacheEnabled,
                cacheTtlSeconds,
                256,
                userApiKeyService,
                metricsService
        );
    }
}


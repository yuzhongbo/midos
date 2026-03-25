package com.zhongbo.mindos.assistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyLlmClientTest {

    @Test
    void shouldUseBuiltInMainlandEndpointWhenProviderEndpointNotConfigured() {
        ApiKeyLlmClient client = newClient(
                "deepseek",
                "fixed",
                "llm-dsl:deepseek,llm-fallback:deepseek",
                "cost:deepseek,quality:deepseek",
                "deepseek:key-deepseek"
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
                "dashscope:key-qwen"
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
                "openai:key-openai,local:key-local"
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
                "openai:key-openai,local:key-local"
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
                "openai:key-openai,local:key-local"
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
                "openai:key-openai,local:key-local"
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
                "openai:key-openai,local:key-local"
        );

        String output = client.generateResponse("hello", Map.of("userId", "u1", "llmPreset", "unknown", "llmProvider", "auto", "routeStage", "llm-fallback"));

        assertTrue(output.startsWith("[LLM local]"));
    }

    private ApiKeyLlmClient newClient(String defaultProvider,
                                      String routingMode,
                                      String stageMap,
                                      String presetMap,
                                      String providerKeys) {
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
                userApiKeyService,
                metricsService
        );
    }
}


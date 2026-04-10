package com.zhongbo.mindos.assistant.llm;

import java.util.Locale;
import java.util.Map;

/**
 * Lightweight provider routing helper to centralize normalization and endpoint resolution.
 * Prepared for later extraction from ApiKeyLlmClient.
 */
public final class LlmProviderRouter {
    private static final Map<String, String> BUILTIN_PROVIDER_ENDPOINTS = Map.ofEntries(
            Map.entry("openrouter", "https://openrouter.ai/api/v1/chat/completions"),
            Map.entry("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
            Map.entry("deepseek", "https://api.deepseek.com/v1/chat/completions")
    );

    private LlmProviderRouter() {}

    public static String normalizeProvider(String provider) {
        if (provider == null) return "";
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    public static String resolveEndpoint(String normalizedProvider, String explicitEndpoint) {
        if (explicitEndpoint != null && !explicitEndpoint.isBlank()) return explicitEndpoint;
        String ep = BUILTIN_PROVIDER_ENDPOINTS.get(normalizedProvider);
        return ep == null ? "" : ep;
    }
}

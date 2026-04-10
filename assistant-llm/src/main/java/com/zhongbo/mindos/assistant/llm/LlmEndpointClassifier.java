package com.zhongbo.mindos.assistant.llm;

import java.util.Locale;

final class LlmEndpointClassifier {

    private LlmEndpointClassifier() {
    }

    static boolean isOpenAiCompatibleProvider(String normalizedProvider, String endpointValue) {
        if (normalizedProvider.contains("local") || normalizedProvider.contains("llama") || normalizedProvider.contains("mpt")) {
            return false;
        }
        if (normalizedProvider.contains("openai")
                || "deepseek".equals(normalizedProvider)
                || "qwen".equals(normalizedProvider)
                || "kimi".equals(normalizedProvider)
                || "doubao".equals(normalizedProvider)
                || "hunyuan".equals(normalizedProvider)
                || "ernie".equals(normalizedProvider)
                || "glm".equals(normalizedProvider)
                || "gemini".equals(normalizedProvider)
                || "grok".equals(normalizedProvider)) {
            return true;
        }
        return endpointValue != null && endpointValue.contains("/chat/completions");
    }

    static boolean isNativeGeminiEndpoint(String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank()) {
            return false;
        }
        return endpointValue.trim().toLowerCase(Locale.ROOT).contains(":generatecontent");
    }

    static boolean isOllamaGenerateEndpoint(String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank()) {
            return false;
        }
        return endpointValue.trim().toLowerCase(Locale.ROOT).contains("/api/generate");
    }

    static boolean isOllamaChatEndpoint(String endpointValue) {
        if (endpointValue == null || endpointValue.isBlank()) {
            return false;
        }
        return endpointValue.trim().toLowerCase(Locale.ROOT).contains("/api/chat");
    }
}

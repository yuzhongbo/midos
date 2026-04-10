package com.zhongbo.mindos.assistant.llm;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Adapter interface for provider-specific LLM calls. Implementations handle HTTP/SDK calls
 * and streaming behavior. Initial scaffolding for refactor D.
 */
public interface LlmProviderAdapter {
    boolean supports(String normalizedProvider, String endpoint);

    String call(String providerName,
                String endpoint,
                String model,
                String prompt,
                Map<String, Object> context,
                String apiKey,
                Consumer<String> deltaConsumer,
                AtomicBoolean streamEmitted);
}

package com.zhongbo.mindos.assistant.llm;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class DelegatingLlmProviderAdapter implements LlmProviderAdapter {

    @FunctionalInterface
    interface SupportPredicate {
        boolean test(String normalizedProvider, String endpoint);
    }

    @FunctionalInterface
    interface CallDelegate {
        String invoke(String providerName,
                      String endpoint,
                      String model,
                      String prompt,
                      Map<String, Object> context,
                      String apiKey,
                      Consumer<String> deltaConsumer,
                      AtomicBoolean streamEmitted);
    }

    private final boolean httpEnabled;
    private final SupportPredicate supportPredicate;
    private final CallDelegate callDelegate;

    DelegatingLlmProviderAdapter(boolean httpEnabled,
                                 SupportPredicate supportPredicate,
                                 CallDelegate callDelegate) {
        this.httpEnabled = httpEnabled;
        this.supportPredicate = supportPredicate;
        this.callDelegate = callDelegate;
    }

    @Override
    public boolean supports(String normalizedProvider, String endpoint) {
        return httpEnabled && supportPredicate.test(normalizedProvider, endpoint);
    }

    @Override
    public String call(String providerName,
                       String endpoint,
                       String model,
                       String prompt,
                       Map<String, Object> context,
                       String apiKey,
                       Consumer<String> deltaConsumer,
                       AtomicBoolean streamEmitted) {
        return callDelegate.invoke(providerName, endpoint, model, prompt, context, apiKey, deltaConsumer, streamEmitted);
    }
}

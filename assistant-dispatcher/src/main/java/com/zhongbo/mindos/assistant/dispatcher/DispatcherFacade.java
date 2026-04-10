package com.zhongbo.mindos.assistant.dispatcher;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Minimal dispatcher facade used by higher-level components and tests.
 * Added to allow tests to mock the dispatcher without inline mocking of concrete classes.
 */
public interface DispatcherFacade {
    com.zhongbo.mindos.assistant.dispatcher.DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext);
    CompletableFuture<com.zhongbo.mindos.assistant.dispatcher.DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext);
    CompletableFuture<com.zhongbo.mindos.assistant.dispatcher.DispatchResult> dispatchStream(String userId, String userInput, Map<String, Object> profileContext, Consumer<String> deltaConsumer);

    boolean isAcceptingRequests();
    long getActiveDispatchCount();
    void beginDrain();
    boolean waitForActiveDispatches(long timeoutMs);
}

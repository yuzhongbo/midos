package com.zhongbo.mindos.assistant.dispatcher;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.LinkedHashMap;

/**
 * Minimal dispatcher facade used by higher-level components and tests.
 * Added to allow tests to mock the dispatcher without inline mocking of concrete classes.
 */
public interface DispatcherFacade {
    com.zhongbo.mindos.assistant.dispatcher.DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext);
    CompletableFuture<com.zhongbo.mindos.assistant.dispatcher.DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext);
    CompletableFuture<com.zhongbo.mindos.assistant.dispatcher.DispatchResult> dispatchStream(String userId, String userInput, Map<String, Object> profileContext, Consumer<String> deltaConsumer);
    default com.zhongbo.mindos.assistant.dispatcher.DispatchResult dispatchMultiAgent(String userId, String userInput, Map<String, Object> profileContext) {
        return dispatch(userId, userInput, multiAgentProfileContext(profileContext));
    }

    default CompletableFuture<com.zhongbo.mindos.assistant.dispatcher.DispatchResult> dispatchMultiAgentAsync(String userId,
                                                                                                            String userInput,
                                                                                                            Map<String, Object> profileContext) {
        return dispatchAsync(userId, userInput, multiAgentProfileContext(profileContext));
    }

    boolean isAcceptingRequests();
    long getActiveDispatchCount();
    void beginDrain();
    boolean waitForActiveDispatches(long timeoutMs);

    default Map<String, Object> multiAgentProfileContext(Map<String, Object> profileContext) {
        Map<String, Object> routed = new LinkedHashMap<>(profileContext == null ? Map.of() : profileContext);
        routed.put("multiAgent", true);
        routed.put("orchestrationMode", "multi-agent");
        return routed;
    }
}

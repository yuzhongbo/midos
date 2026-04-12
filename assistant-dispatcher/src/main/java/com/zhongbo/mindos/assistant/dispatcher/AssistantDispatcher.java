package com.zhongbo.mindos.assistant.dispatcher;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class AssistantDispatcher {

    private final DispatcherFacade dispatcherService;

    public AssistantDispatcher(DispatcherFacade dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    public DispatchResult dispatch(String userId, String input) {
        return dispatcherService.dispatch(userId, input, Map.of());
    }

    public DispatchResult dispatchMultiAgent(String userId, String input) {
        return dispatcherService.dispatchMultiAgent(userId, input, Map.of());
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String input) {
        return dispatcherService.dispatchAsync(userId, input, Map.of());
    }

    public CompletableFuture<DispatchResult> dispatchMultiAgentAsync(String userId, String input) {
        return dispatcherService.dispatchMultiAgentAsync(userId, input, Map.of());
    }
}

package com.zhongbo.mindos.assistant.dispatcher;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AssistantDispatcher {

    private final DispatcherService dispatcherService;

    public AssistantDispatcher(DispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    public DispatchResult dispatch(String userId, String input) {
        return dispatcherService.dispatch(userId, input);
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String input) {
        return dispatcherService.dispatchAsync(userId, input);
    }
}


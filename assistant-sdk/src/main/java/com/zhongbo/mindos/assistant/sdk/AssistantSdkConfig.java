package com.zhongbo.mindos.assistant.sdk;

import java.net.URI;

/**
 * SDK configuration skeleton.
 */
public record AssistantSdkConfig(URI baseUri) {

    public static AssistantSdkConfig localDefault() {
        return new AssistantSdkConfig(URI.create("http://localhost:8080"));
    }
}


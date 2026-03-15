package com.zhongbo.mindos.assistant.sdk;

public class AssistantSdkException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public AssistantSdkException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }
}


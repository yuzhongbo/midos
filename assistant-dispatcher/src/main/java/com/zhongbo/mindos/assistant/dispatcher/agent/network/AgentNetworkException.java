package com.zhongbo.mindos.assistant.dispatcher.agent.network;

public class AgentNetworkException extends RuntimeException {

    public AgentNetworkException(String message) {
        super(message);
    }

    public AgentNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}

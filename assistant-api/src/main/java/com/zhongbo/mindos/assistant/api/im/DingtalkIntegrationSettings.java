package com.zhongbo.mindos.assistant.api.im;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class DingtalkIntegrationSettings {

    static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";

    private final boolean imEnabled;
    private final boolean dingtalkEnabled;
    private final boolean streamEnabled;
    private final String streamClientId;
    private final String streamClientSecret;
    private final String streamTopic;
    private final long streamWaitingDelayMs;
    private final String streamWaitingText;
    private final boolean streamForceWaiting;
    private final long streamFinalTimeoutMs;
    private final boolean streamReconnectEnabled;
    private final long streamReconnectInitialDelayMs;
    private final long streamReconnectMaxDelayMs;
    private final double streamReconnectMultiplier;
    private final double streamReconnectJitterRatio;
    private final int streamReconnectMaxAttempts;
    private final boolean outboundEnabled;
    private final String outboundRobotCode;
    private final String outboundAppKey;
    private final String outboundAppSecret;

    public DingtalkIntegrationSettings(
            @Value("${mindos.im.enabled:false}") boolean imEnabled,
            @Value("${mindos.im.dingtalk.enabled:false}") boolean dingtalkEnabled,
            @Value("${mindos.im.dingtalk.stream.enabled:false}") boolean streamEnabled,
            @Value("${mindos.im.dingtalk.stream.client-id:}") String streamClientId,
            @Value("${mindos.im.dingtalk.stream.client-secret:}") String streamClientSecret,
            @Value("${mindos.im.dingtalk.stream.topic:/v1.0/im/bot/messages/get}") String streamTopic,
            @Value("${mindos.im.dingtalk.stream.waiting-delay-ms:800}") long streamWaitingDelayMs,
            @Value("${mindos.im.dingtalk.stream.waiting-text:\u5DF2\u6536\u5230\uFF0C\u6B63\u5728\u5904\u7406\uFF0C\u7A0D\u540E\u7ED9\u4F60\u5B8C\u6574\u56DE\u590D\u3002}") String streamWaitingText,
            @Value("${mindos.im.dingtalk.stream.force-waiting:false}") boolean streamForceWaiting,
            @Value("${mindos.im.dingtalk.stream.final-timeout-ms:30000}") long streamFinalTimeoutMs,
            @Value("${mindos.im.dingtalk.stream.reconnect.enabled:true}") boolean streamReconnectEnabled,
            @Value("${mindos.im.dingtalk.stream.reconnect.initial-delay-ms:1000}") long streamReconnectInitialDelayMs,
            @Value("${mindos.im.dingtalk.stream.reconnect.max-delay-ms:60000}") long streamReconnectMaxDelayMs,
            @Value("${mindos.im.dingtalk.stream.reconnect.multiplier:2.0}") double streamReconnectMultiplier,
            @Value("${mindos.im.dingtalk.stream.reconnect.jitter-ratio:0.2}") double streamReconnectJitterRatio,
            @Value("${mindos.im.dingtalk.stream.reconnect.max-attempts:0}") int streamReconnectMaxAttempts,
            @Value("${mindos.im.dingtalk.outbound.enabled:false}") boolean outboundEnabled,
            @Value("${mindos.im.dingtalk.outbound.robot-code:}") String outboundRobotCode,
            @Value("${mindos.im.dingtalk.outbound.app-key:}") String outboundAppKey,
            @Value("${mindos.im.dingtalk.outbound.app-secret:}") String outboundAppSecret) {
        this.imEnabled = imEnabled;
        this.dingtalkEnabled = dingtalkEnabled;
        this.streamEnabled = streamEnabled;
        this.streamClientId = trim(streamClientId);
        this.streamClientSecret = trim(streamClientSecret);
        this.streamTopic = normalizeStreamTopic(streamTopic);
        this.streamWaitingDelayMs = Math.max(0L, streamWaitingDelayMs);
        this.streamWaitingText = trim(streamWaitingText).isBlank()
                ? "已收到，正在处理，稍后给你完整回复。"
                : trim(streamWaitingText);
        this.streamForceWaiting = streamForceWaiting;
        this.streamFinalTimeoutMs = Math.max(1000L, streamFinalTimeoutMs);
        this.streamReconnectEnabled = streamReconnectEnabled;
        this.streamReconnectInitialDelayMs = Math.max(200L, streamReconnectInitialDelayMs);
        this.streamReconnectMaxDelayMs = Math.max(this.streamReconnectInitialDelayMs, streamReconnectMaxDelayMs);
        this.streamReconnectMultiplier = Math.max(1.0d, streamReconnectMultiplier);
        this.streamReconnectJitterRatio = Math.max(0.0d, Math.min(0.5d, streamReconnectJitterRatio));
        this.streamReconnectMaxAttempts = Math.max(0, streamReconnectMaxAttempts);
        this.outboundEnabled = outboundEnabled;
        this.outboundRobotCode = trim(outboundRobotCode);
        this.outboundAppKey = trim(outboundAppKey);
        this.outboundAppSecret = trim(outboundAppSecret);
    }


    boolean streamModeEnabled() {
        return imEnabled && dingtalkEnabled && streamEnabled;
    }

    boolean outboundMessagingEnabled() {
        return imEnabled && dingtalkEnabled && outboundEnabled;
    }

    boolean hasStreamCredentials() {
        return !streamClientId.isBlank() && !streamClientSecret.isBlank();
    }

    boolean hasOutboundCredentials() {
        return !effectiveOutboundAppKey().isBlank()
                && !effectiveOutboundAppSecret().isBlank()
                && !effectiveRobotCode().isBlank();
    }

    String streamClientId() {
        return streamClientId;
    }

    String streamClientSecret() {
        return streamClientSecret;
    }

    String streamTopic() {
        return streamTopic;
    }

    long streamWaitingDelayMs() {
        return streamWaitingDelayMs;
    }

    String streamWaitingText() {
        return streamWaitingText;
    }

    boolean streamForceWaiting() {
        return streamForceWaiting;
    }

    long streamFinalTimeoutMs() {
        return streamFinalTimeoutMs;
    }

    boolean streamReconnectEnabled() {
        return streamReconnectEnabled;
    }

    long streamReconnectInitialDelayMs() {
        return streamReconnectInitialDelayMs;
    }

    long streamReconnectMaxDelayMs() {
        return streamReconnectMaxDelayMs;
    }

    double streamReconnectMultiplier() {
        return streamReconnectMultiplier;
    }

    double streamReconnectJitterRatio() {
        return streamReconnectJitterRatio;
    }

    int streamReconnectMaxAttempts() {
        return streamReconnectMaxAttempts;
    }

    String effectiveRobotCode() {
        return outboundRobotCode;
    }

    String effectiveOutboundAppKey() {
        return outboundAppKey.isBlank() ? streamClientId : outboundAppKey;
    }

    String effectiveOutboundAppSecret() {
        return outboundAppSecret.isBlank() ? streamClientSecret : outboundAppSecret;
    }

    String missingReadinessReason() {
        if (!streamModeEnabled()) {
            return "stream mode disabled";
        }
        if (!hasStreamCredentials()) {
            return "missing stream credentials";
        }
        if (!outboundMessagingEnabled()) {
            return "outbound messaging disabled";
        }
        if (!hasOutboundCredentials()) {
            return "missing outbound robot credentials";
        }
        return "ready";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeStreamTopic(String value) {
        String normalized = trim(value);
        if (normalized.isBlank()
                || "chatbot".equalsIgnoreCase(normalized)
                || "bot".equalsIgnoreCase(normalized)) {
            return BOT_MESSAGE_TOPIC;
        }
        return normalized;
    }
}


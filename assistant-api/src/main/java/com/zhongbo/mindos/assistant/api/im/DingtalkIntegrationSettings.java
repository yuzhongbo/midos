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
    private final boolean outboundEnabled;
    private final String outboundRobotCode;
    private final String outboundAppKey;
    private final String outboundAppSecret;

    DingtalkIntegrationSettings(
            @Value("${mindos.im.enabled:false}") boolean imEnabled,
            @Value("${mindos.im.dingtalk.enabled:false}") boolean dingtalkEnabled,
            @Value("${mindos.im.dingtalk.stream.enabled:false}") boolean streamEnabled,
            @Value("${mindos.im.dingtalk.stream.client-id:}") String streamClientId,
            @Value("${mindos.im.dingtalk.stream.client-secret:}") String streamClientSecret,
            @Value("${mindos.im.dingtalk.stream.topic:chatbot}") String streamTopic,
            @Value("${mindos.im.dingtalk.stream.waiting-delay-ms:800}") long streamWaitingDelayMs,
            @Value("${mindos.im.dingtalk.stream.waiting-text:我正在处理这条消息，请稍等，我会继续回复你。}") String streamWaitingText,
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
                ? "我正在处理这条消息，请稍等，我会继续回复你。"
                : trim(streamWaitingText);
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


package com.zhongbo.mindos.assistant.api.im;

interface DingtalkConversationSender {

    boolean isReady();

    boolean sendText(String openConversationId, String text);

    default boolean sendText(String openConversationId, String text, String sessionWebhook) {
        return sendText(openConversationId, text);
    }
}


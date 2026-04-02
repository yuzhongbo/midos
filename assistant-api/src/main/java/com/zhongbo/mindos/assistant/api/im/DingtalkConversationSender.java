package com.zhongbo.mindos.assistant.api.im;

interface DingtalkConversationSender {

    boolean isReady();

    boolean sendText(String openConversationId, String text);

    default boolean sendText(String openConversationId, String text, String sessionWebhook) {
        return sendText(openConversationId, text);
    }

    default boolean sendMarkdownCard(String openConversationId,
                                     String title,
                                     String markdown,
                                     String sessionWebhook) {
        return sendText(openConversationId, markdown);
    }

    default DingtalkMessageHandle sendMarkdownCardHandle(String openConversationId,
                                                         String title,
                                                         String markdown,
                                                         String sessionWebhook) {
        boolean sent = sendMarkdownCard(openConversationId, title, markdown, sessionWebhook);
        return sent
                ? DingtalkMessageHandle.sentWithoutUpdate(openConversationId, sessionWebhook)
                : DingtalkMessageHandle.notSent(openConversationId, sessionWebhook);
    }

    default boolean updateMessage(String openConversationId,
                                  String updateText,
                                  String sessionWebhook) {
        return sendText(openConversationId, updateText, sessionWebhook);
    }

    default boolean updateMessage(DingtalkMessageHandle handle,
                                  String title,
                                  String markdown,
                                  String sessionWebhook) {
        if (handle == null || !handle.sent()) {
            return false;
        }
        return updateMessage(handle.conversationId(), markdown, sessionWebhook);
    }
}


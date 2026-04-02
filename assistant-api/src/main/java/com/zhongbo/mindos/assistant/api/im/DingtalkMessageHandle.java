package com.zhongbo.mindos.assistant.api.im;

record DingtalkMessageHandle(
        String conversationId,
        String sessionWebhook,
        String platformMessageId,
        boolean sent,
        boolean canUpdate
) {

    static DingtalkMessageHandle notSent(String conversationId, String sessionWebhook) {
        return new DingtalkMessageHandle(normalize(conversationId), normalize(sessionWebhook), "", false, false);
    }

    static DingtalkMessageHandle sentWithoutUpdate(String conversationId, String sessionWebhook) {
        return new DingtalkMessageHandle(normalize(conversationId), normalize(sessionWebhook), "", true, false);
    }

    static DingtalkMessageHandle updatable(String conversationId, String sessionWebhook, String platformMessageId) {
        return new DingtalkMessageHandle(normalize(conversationId), normalize(sessionWebhook), normalize(platformMessageId), true, true);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}


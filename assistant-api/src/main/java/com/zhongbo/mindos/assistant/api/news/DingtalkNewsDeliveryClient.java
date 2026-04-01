package com.zhongbo.mindos.assistant.api.news;

import com.dingtalk.open.app.api.chatbot.BotReplier;
import com.zhongbo.mindos.assistant.api.im.DingtalkOpenApiMessageClient;
import org.springframework.stereotype.Component;

import java.util.logging.Level;
import java.util.logging.Logger;

@Component
class DingtalkNewsDeliveryClient implements NewsDeliveryClient {

    private static final Logger LOGGER = Logger.getLogger(DingtalkNewsDeliveryClient.class.getName());

    private final DingtalkOpenApiMessageClient dingtalkOpenApiMessageClient;

    DingtalkNewsDeliveryClient(DingtalkOpenApiMessageClient dingtalkOpenApiMessageClient) {
        this.dingtalkOpenApiMessageClient = dingtalkOpenApiMessageClient;
    }

    @Override
    public boolean deliver(String message, NewsPushConfig config) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isBlank()) {
            LOGGER.fine("NewsDeliveryClient: empty message, skip deliver");
            return false;
        }
        if (trySessionWebhook(trimmed, config.sessionWebhook())) {
            return true;
        }
        if (config.openConversationId().isBlank()) {
            LOGGER.warning("NewsDeliveryClient: no DingTalk destination configured");
            return false;
        }
        return dingtalkOpenApiMessageClient.sendText(
                config.senderId().isBlank() ? "news-bot" : config.senderId(),
                config.openConversationId(),
                trimmed
        );
    }

    private boolean trySessionWebhook(String message, String sessionWebhook) {
        String webhook = sessionWebhook == null ? "" : sessionWebhook.trim();
        if (webhook.isBlank()) {
            return false;
        }
        try {
            BotReplier.fromWebhook(webhook).replyText(message);
            return true;
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "NewsDeliveryClient: sessionWebhook send failed", ex);
            return false;
        }
    }
}

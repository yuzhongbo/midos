package com.zhongbo.mindos.assistant.api.news;

import java.util.List;

public record NewsConfigRequest(
        Boolean enabled,
        List<String> sources,
        Integer maxItems,
        String cron,
        String timezone,
        String sessionWebhook,
        String openConversationId,
        String senderId,
        Integer messageMaxChars
) {
}

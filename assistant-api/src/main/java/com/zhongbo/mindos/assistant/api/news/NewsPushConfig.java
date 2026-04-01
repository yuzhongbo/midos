package com.zhongbo.mindos.assistant.api.news;

import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

record NewsPushConfig(
        boolean enabled,
        List<String> sources,
        int maxItems,
        String cron,
        String timezone,
        String sessionWebhook,
        String openConversationId,
        String senderId,
        int messageMaxChars
) {

    NewsPushConfig normalize() {
        List<String> normalizedSources = new ArrayList<>();
        if (sources != null) {
            for (String src : sources) {
                if (src != null) {
                    String trimmed = src.trim();
                    if (!trimmed.isBlank()) {
                        normalizedSources.add(trimmed);
                    }
                }
            }
        }
        int cappedMaxItems = Math.max(1, Math.min(maxItems, 20));
        int cappedMessageMaxChars = Math.max(200, Math.min(messageMaxChars, 4000));
        return new NewsPushConfig(
                enabled,
                Collections.unmodifiableList(normalizedSources),
                cappedMaxItems,
                cron == null ? "0 0 9 * * *" : cron.trim(),
                timezone == null ? ZoneId.systemDefault().getId() : timezone.trim(),
                sessionWebhook == null ? "" : sessionWebhook.trim(),
                openConversationId == null ? "" : openConversationId.trim(),
                senderId == null ? "" : senderId.trim(),
                cappedMessageMaxChars
        );
    }

    ZoneId zoneIdOrDefault() {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    CronExpression cronExpression() {
        return CronExpression.parse(cron);
    }
}

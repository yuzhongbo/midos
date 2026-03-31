package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingtalkIntegrationSettingsTest {

    @Test
    void shouldDefaultTopicToBotMessagePathWhenBlank() {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                "",
                800L,
                "waiting",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );

        assertEquals(DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC, settings.streamTopic());
    }

    @Test
    void shouldMapLegacyChatbotAliasToBotMessagePath() {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                "chatbot",
                800L,
                "waiting",
                false,
                30000L,
                true,
                1000L,
                60000L,
                2.0d,
                0.2d,
                0,
                true,
                "robot-code",
                "",
                ""
        );

        assertEquals(DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC, settings.streamTopic());
    }

    @Test
    void shouldNormalizeReconnectBackoffBounds() {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                800L,
                "waiting",
                false,
                30000L,
                true,
                50L,
                100L,
                0.5d,
                1.8d,
                -2,
                true,
                "robot-code",
                "",
                ""
        );

        assertTrue(settings.streamReconnectEnabled());
        assertEquals(200L, settings.streamReconnectInitialDelayMs());
        assertEquals(200L, settings.streamReconnectMaxDelayMs());
        assertEquals(1.0d, settings.streamReconnectMultiplier());
        assertEquals(0.5d, settings.streamReconnectJitterRatio());
        assertEquals(0, settings.streamReconnectMaxAttempts());
    }
}


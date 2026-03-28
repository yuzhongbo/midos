package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                true,
                "robot-code",
                "",
                ""
        );

        assertEquals(DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC, settings.streamTopic());
    }
}


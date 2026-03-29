package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DingtalkStreamModeLifecycleTest {

    @Test
    void shouldCreateSdkCompatibleTypedCallbackListener() throws Exception {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                800L,
                "waiting",
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
        DingtalkStreamMessageDispatcher dispatcher = mock(DingtalkStreamMessageDispatcher.class);
        DingtalkStreamModeLifecycle lifecycle = new DingtalkStreamModeLifecycle(settings, dispatcher);

        Method createCallbackListener = DingtalkStreamModeLifecycle.class.getDeclaredMethod("createCallbackListener");
        createCallbackListener.setAccessible(true);
        Object listener = createCallbackListener.invoke(lifecycle);

        Class<?> callbackInterface = Class.forName("com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener");
        Class<?> callbackDescriptor = Class.forName("com.dingtalk.open.app.api.callback.CallbackDescriptor");
        Method buildDescriptor = callbackDescriptor.getDeclaredMethod("build", callbackInterface);
        buildDescriptor.setAccessible(true);
        Object descriptor = buildDescriptor.invoke(null, listener);

        assertNotNull(descriptor);
        lifecycle.shutdown();
    }

    @Test
    void shouldResolveFluentMethodWhenArgumentIsSubtype() throws Exception {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                800L,
                "waiting",
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
        DingtalkStreamMessageDispatcher dispatcher = mock(DingtalkStreamMessageDispatcher.class);
        DingtalkStreamModeLifecycle lifecycle = new DingtalkStreamModeLifecycle(settings, dispatcher);

        FakeBuilder target = new FakeBuilder();
        Method invokeFluent = DingtalkStreamModeLifecycle.class
                .getDeclaredMethod("invokeFluent", Object.class, String.class, Class.class, Object.class);
        invokeFluent.setAccessible(true);
        Object returned = invokeFluent.invoke(lifecycle, target, "credential", FakeCredential.class, new FakeAuthCredential());

        assertNotNull(returned);
        assertEquals("ok", target.lastCredentialValue);
        lifecycle.shutdown();
    }

    private interface ParentCredential {
    }

    private static class FakeCredential implements ParentCredential {
    }

    private static final class FakeAuthCredential extends FakeCredential {
        private final String value = "ok";
    }

    private static final class FakeBuilder {
        private String lastCredentialValue;

        public FakeBuilder credential(ParentCredential credential) {
            if (credential instanceof FakeAuthCredential authCredential) {
                this.lastCredentialValue = authCredential.value;
            }
            return this;
        }
    }

    @Test
    void shouldRetryFor503FailuresWithinAttemptBudget() {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                800L,
                "waiting",
                true,
                1000L,
                60000L,
                2.0d,
                0.0d,
                3,
                true,
                "robot-code",
                "",
                ""
        );
        DingtalkStreamModeLifecycle lifecycle = new DingtalkStreamModeLifecycle(settings, mock(DingtalkStreamMessageDispatcher.class));

        assertTrue(lifecycle.shouldRetryStartFailure(new RuntimeException("status=503 temporary"), 1));
        assertTrue(lifecycle.shouldRetryStartFailure(new RuntimeException("ServiceUnavailable"), 2));
        assertFalse(lifecycle.shouldRetryStartFailure(new RuntimeException("status=503 temporary"), 3));

        lifecycle.shutdown();
    }

    @Test
    void shouldComputeExponentialBackoffWithCapWhenJitterDisabled() {
        DingtalkIntegrationSettings settings = new DingtalkIntegrationSettings(
                true,
                true,
                true,
                "client-id",
                "client-secret",
                DingtalkIntegrationSettings.BOT_MESSAGE_TOPIC,
                800L,
                "waiting",
                true,
                1000L,
                3000L,
                2.0d,
                0.0d,
                0,
                true,
                "robot-code",
                "",
                ""
        );
        DingtalkStreamModeLifecycle lifecycle = new DingtalkStreamModeLifecycle(settings, mock(DingtalkStreamMessageDispatcher.class));

        assertEquals(1000L, lifecycle.computeReconnectDelayMs(1));
        assertEquals(2000L, lifecycle.computeReconnectDelayMs(2));
        assertEquals(3000L, lifecycle.computeReconnectDelayMs(3));
        assertEquals(3000L, lifecycle.computeReconnectDelayMs(6));

        lifecycle.shutdown();
    }
}



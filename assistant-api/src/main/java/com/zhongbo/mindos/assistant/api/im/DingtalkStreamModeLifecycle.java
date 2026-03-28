package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
class DingtalkStreamModeLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = Logger.getLogger(DingtalkStreamModeLifecycle.class.getName());

    private final DingtalkIntegrationSettings settings;
    private final DingtalkStreamMessageDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Future<?> streamFuture;
    private volatile Object streamClient;

    DingtalkStreamModeLifecycle(DingtalkIntegrationSettings settings,
                                DingtalkStreamMessageDispatcher dispatcher) {
        this.settings = settings;
        this.dispatcher = dispatcher;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.streamExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mindos-dingtalk-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        if (running.get()) {
            return;
        }
        if (!settings.streamModeEnabled()) {
            logEvent(Level.INFO, "dingtalk.stream.lifecycle.skipped", Map.of(
                    "reason", "stream_mode_disabled"
            ));
            return;
        }
        String readiness = settings.missingReadinessReason();
        if (!"ready".equals(readiness)) {
            logEvent(Level.WARNING, "dingtalk.stream.lifecycle.not-started", Map.of(
                    "reason", readiness
            ));
            return;
        }
        running.set(true);
        logEvent(Level.INFO, "dingtalk.stream.lifecycle.starting", Map.of(
                "topic", settings.streamTopic()
        ));
        streamFuture = streamExecutor.submit(this::runStreamClient);
    }

    private void runStreamClient() {
        try {
            Object client = buildClient();
            this.streamClient = client;
            logEvent(Level.INFO, "dingtalk.stream.lifecycle.client-starting", Map.of(
                    "topic", settings.streamTopic()
            ));
            invokeNoArgs(client, "start");
            logEvent(Level.INFO, "dingtalk.stream.lifecycle.started", Map.of(
                    "topic", settings.streamTopic()
            ));
        } catch (Throwable ex) {
            running.set(false);
            logEvent(Level.WARNING, "dingtalk.stream.lifecycle.start-failed", Map.of(
                    "topic", settings.streamTopic(),
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ), ex);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        closeClientQuietly();
        Future<?> future = streamFuture;
        if (future != null) {
            future.cancel(true);
        }
    }

    @Override
    public void stop(@NonNull Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @PreDestroy
    void shutdown() {
        stop();
        streamExecutor.shutdownNow();
    }

    private Object buildClient() throws Exception {
        Class<?> builderClass = Class.forName("com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder");
        Object builder = builderClass.getMethod("custom").invoke(null);

        Class<?> credentialClass = Class.forName("com.dingtalk.open.app.api.security.AuthClientCredential");
        Object credential = credentialClass
                .getConstructor(String.class, String.class)
                .newInstance(settings.streamClientId(), settings.streamClientSecret());
        builder = invokeFluent(builder, "credential", credentialClass, credential);

        Class<?> listenerClass = Class.forName("com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener");
        Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                createCallbackInvocationHandler());
        builder = invokeFluent(builder, "registerCallbackListener", String.class, settings.streamTopic(), listenerClass, listener);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private InvocationHandler createCallbackInvocationHandler() {
        return (proxy, method, args) -> {
            String methodName = method.getName();
            if ("execute".equals(methodName) && args != null && args.length == 1) {
                dispatcher.handleIncomingPayload(toPayloadMap(args[0]));
                return dispatcher.emptyAck();
            }
            if ("toString".equals(methodName)) {
                return "MindOSDingTalkStreamListener";
            }
            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(methodName)) {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            return null;
        };
    }

    private Map<String, Object> toPayloadMap(Object payload) {
        if (payload == null) {
            return Map.of();
        }
        if (payload instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> converted.put(String.valueOf(key), value));
            return converted;
        }
        try {
            return objectMapper.convertValue(payload, new TypeReference<>() {
            });
        } catch (IllegalArgumentException ex) {
            LOGGER.log(Level.WARNING, "Failed to convert DingTalk stream payload into map", ex);
            return Map.of();
        }
    }

    private Object invokeFluent(Object target, String methodName, Class<?> firstType, Object firstArg, Class<?> secondType, Object secondArg)
            throws Exception {
        Method method = target.getClass().getMethod(methodName, firstType, secondType);
        return method.invoke(target, firstArg, secondArg);
    }

    private Object invokeFluent(Object target, String methodName, Class<?> argumentType, Object argument) throws Exception {
        Method method = target.getClass().getMethod(methodName, argumentType);
        return method.invoke(target, argument);
    }

    private void invokeNoArgs(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        method.invoke(target);
    }

    private void closeClientQuietly() {
        Object client = this.streamClient;
        this.streamClient = null;
        if (client == null) {
            return;
        }
        for (String candidate : new String[]{"stop", "close", "shutdown"}) {
            try {
                invokeNoArgs(client, candidate);
                return;
            } catch (NoSuchMethodException ignored) {
                // Try the next lifecycle method name.
            } catch (Exception ex) {
                LOGGER.log(Level.FINE, "Failed to stop DingTalk stream client via " + candidate, ex);
                return;
            }
        }
    }

    private void logEvent(Level level, String eventName, Map<String, Object> fields) {
        logEvent(level, eventName, fields, null);
    }

    private void logEvent(Level level, String eventName, Map<String, Object> fields, Throwable throwable) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", eventName);
        if (fields != null) {
            event.putAll(fields);
        }
        try {
            String message = objectMapper.writeValueAsString(event);
            if (throwable == null) {
                LOGGER.log(level, message);
            } else {
                LOGGER.log(level, message, throwable);
            }
        } catch (Exception ex) {
            if (throwable == null) {
                LOGGER.log(level, event.toString(), ex);
            } else {
                LOGGER.log(level, event.toString(), throwable);
            }
        }
    }
}


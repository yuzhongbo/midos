package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
        int attempt = 0;
        while (running.get()) {
            attempt++;
            try {
                Object client = buildClient();
                this.streamClient = client;
                logEvent(Level.INFO, "dingtalk.stream.lifecycle.client-starting", Map.of(
                        "topic", settings.streamTopic(),
                        "attempt", attempt
                ));
                if (client instanceof OpenDingTalkClient streamClientContract) {
                    streamClientContract.start();
                } else {
                    invokeNoArgs(client, "start");
                }
                logEvent(Level.INFO, "dingtalk.stream.lifecycle.started", Map.of(
                        "topic", settings.streamTopic(),
                        "attempt", attempt
                ));
                return;
            } catch (Throwable ex) {
                closeClientQuietly();
                if (!running.get()) {
                    return;
                }
                if (!shouldRetryStartFailure(ex, attempt)) {
                    running.set(false);
                    logEvent(Level.WARNING, "dingtalk.stream.lifecycle.start-failed", Map.of(
                            "topic", settings.streamTopic(),
                            "attempt", attempt,
                            "reason", failureReason(ex)
                    ), ex);
                    return;
                }
                long delayMs = computeReconnectDelayMs(attempt);
                logEvent(Level.WARNING, "dingtalk.stream.lifecycle.retry-scheduled", Map.of(
                        "topic", settings.streamTopic(),
                        "attempt", attempt,
                        "delayMs", delayMs,
                        "reason", failureReason(ex)
                ), ex);
                if (!sleepBeforeRetry(delayMs)) {
                    return;
                }
            }
        }
    }

    boolean shouldRetryStartFailure(Throwable throwable, int attempt) {
        if (!settings.streamReconnectEnabled()) {
            return false;
        }
        int maxAttempts = settings.streamReconnectMaxAttempts();
        if (maxAttempts > 0 && attempt >= maxAttempts) {
            return false;
        }
        String reason = failureReason(throwable).toLowerCase();
        if (reason.contains("status=503") || reason.contains("serviceunavailable")) {
            return true;
        }
        return attempt <= 2;
    }

    long computeReconnectDelayMs(int attempt) {
        long initial = settings.streamReconnectInitialDelayMs();
        long max = settings.streamReconnectMaxDelayMs();
        double multiplier = settings.streamReconnectMultiplier();
        double backoff = initial * Math.pow(multiplier, Math.max(0, attempt - 1));
        long clamped = (long) Math.min(max, Math.max(initial, backoff));
        return applyJitter(clamped);
    }

    private long applyJitter(long baseDelayMs) {
        double jitterRatio = settings.streamReconnectJitterRatio();
        if (jitterRatio <= 0.0d) {
            return baseDelayMs;
        }
        long spread = Math.max(1L, Math.round(baseDelayMs * jitterRatio));
        long delta = ThreadLocalRandom.current().nextLong(-spread, spread + 1L);
        return Math.max(200L, baseDelayMs + delta);
    }

    private boolean sleepBeforeRetry(long delayMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(0L, delayMs));
            return running.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running.set(false);
            return false;
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
        Class<?> credentialContractClass = Class.forName("com.dingtalk.open.app.api.security.DingTalkCredential");
        try {
            builder = invokeFluent(builder, "credential", credentialContractClass, credential);
        } catch (NoSuchMethodException ex) {
            // Older SDK variants may expose the concrete AuthClientCredential signature directly.
            builder = invokeFluent(builder, "credential", credentialClass, credential);
        }

        Class<?> listenerClass = Class.forName("com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener");
        OpenDingTalkCallbackListener<ChatbotMessage, Map<String, Object>> listener = createCallbackListener();
        builder = invokeFluent(builder, "registerCallbackListener", String.class, settings.streamTopic(), listenerClass, listener);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private OpenDingTalkCallbackListener<ChatbotMessage, Map<String, Object>> createCallbackListener() {
        // Use a named class with concrete generic parameters; SDK callback descriptor parsing rejects raw/lambda listeners.
        return new ChatbotStreamCallbackListener();
    }

    private final class ChatbotStreamCallbackListener implements OpenDingTalkCallbackListener<ChatbotMessage, Map<String, Object>> {
        @Override
        public Map<String, Object> execute(ChatbotMessage payload) {
            dispatcher.handleIncomingPayload(toPayloadMap(payload));
            return dispatcher.emptyAck();
        }
    }

    private String failureReason(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getTargetException() != null) {
            Throwable target = invocationTargetException.getTargetException();
            return target.getMessage() == null ? target.getClass().getSimpleName() : target.getMessage();
        }
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
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
        Method method;
        try {
            method = target.getClass().getMethod(methodName, argumentType);
        } catch (NoSuchMethodException ex) {
            method = null;
            for (Method candidate : target.getClass().getMethods()) {
                if (!candidate.getName().equals(methodName) || candidate.getParameterCount() != 1) {
                    continue;
                }
                Class<?> parameterType = candidate.getParameterTypes()[0];
                if (parameterType.isAssignableFrom(argumentType)) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) {
                throw ex;
            }
        }
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
        if (client instanceof OpenDingTalkClient streamClientContract) {
            try {
                streamClientContract.stop();
                return;
            } catch (Exception ex) {
                LOGGER.log(Level.FINE, "Failed to stop DingTalk stream client via OpenDingTalkClient.stop", ex);
            }
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


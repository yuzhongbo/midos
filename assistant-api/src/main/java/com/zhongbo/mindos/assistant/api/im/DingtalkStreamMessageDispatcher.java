package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
class DingtalkStreamMessageDispatcher {

    private static final Logger LOGGER = Logger.getLogger(DingtalkStreamMessageDispatcher.class.getName());
    private static final long ECHO_SUPPRESSION_TTL_MILLIS = 180_000L;
    private static final int ECHO_SUPPRESSION_MAX_ENTRIES = 256;

    private final ImGatewayService imGatewayService;
    private final DingtalkConversationSender conversationSender;
    private final DingtalkIntegrationSettings settings;
    private final ScheduledExecutorService waitingScheduler;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, Long> recentBotEchoes = new ConcurrentHashMap<>();

    DingtalkStreamMessageDispatcher(ImGatewayService imGatewayService,
                                    DingtalkConversationSender conversationSender,
                                    DingtalkIntegrationSettings settings) {
        this.imGatewayService = imGatewayService;
        this.conversationSender = conversationSender;
        this.settings = settings;
        this.waitingScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mindos-dingtalk-stream-waiting");
            thread.setDaemon(true);
            return thread;
        });
    }

    void handleIncomingPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> eventData = nestedMapValue(payload.get("data"));
        String conversationId = firstNonBlank(
                stringValue(payload.get("conversationId")),
                stringValue(payload.get("openConversationId")),
                stringValue(eventData.get("conversationId")),
                stringValue(eventData.get("openConversationId"))
        );
        String senderId = firstNonBlank(
                stringValue(payload.get("senderId")),
                stringValue(payload.get("senderStaffId")),
                stringValue(payload.get("staffId")),
                nestedStringValue(payload, "sender", "staffId"),
                nestedStringValue(payload, "sender", "senderId"),
                stringValue(eventData.get("senderId")),
                stringValue(eventData.get("senderStaffId")),
                stringValue(eventData.get("staffId")),
                nestedStringValue(eventData, "sender", "staffId"),
                nestedStringValue(eventData, "sender", "senderId")
        );
        String text = firstNonBlank(
                nestedStringValue(payload, "text", "content"),
                nestedStringValue(payload, "content", "content"),
                stringValue(payload.get("content")),
                stringValue(payload.get("msgContent")),
                nestedStringValue(eventData, "text", "content"),
                nestedStringValue(eventData, "content", "content"),
                stringValue(eventData.get("content")),
                stringValue(eventData.get("msgContent"))
        );
        if (conversationId.isBlank() || text.isBlank()) {
            logEvent(Level.WARNING, "dingtalk.stream.received.invalid", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "senderHash", safeHash(senderId),
                    "textLength", text == null ? 0 : text.length()
            ));
            return;
        }
        logEvent(Level.INFO, "dingtalk.stream.received", Map.of(
                "conversationHash", safeHash(conversationId),
                "senderHash", safeHash(senderId),
                "textLength", text.length(),
                "waitingDelayMs", settings.streamWaitingDelayMs()
        ));
        if (shouldSuppressAsBotEcho(conversationId, text)) {
            logEvent(Level.INFO, "dingtalk.stream.self-echo.suppressed", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "senderHash", safeHash(senderId),
                    "textLength", text.length()
            ));
            return;
        }
        if (!conversationSender.isReady()) {
            logEvent(Level.WARNING, "dingtalk.stream.received.dropped", Map.of(
                    "reason", "outbound_sender_not_ready",
                    "conversationHash", safeHash(conversationId),
                    "senderHash", safeHash(senderId),
                    "textLength", text.length()
            ));
            return;
        }

        CompletableFuture<String> replyFuture = imGatewayService.chatAsync(ImPlatform.DINGTALK, senderId, conversationId, text);
        AtomicBoolean waitingSent = new AtomicBoolean(false);
        ScheduledFuture<?> waitingTask = waitingScheduler.schedule(() -> {
            if (replyFuture.isDone()) {
                return;
            }
            boolean sent = sendText(conversationId, settings.streamWaitingText());
            waitingSent.set(sent);
            logEvent(sent ? Level.INFO : Level.WARNING,
                    sent ? "dingtalk.stream.waiting.sent" : "dingtalk.stream.waiting.failed",
                    Map.of(
                            "conversationHash", safeHash(conversationId),
                            "senderHash", safeHash(senderId),
                            "replyLength", settings.streamWaitingText().length(),
                            "waitingDelayMs", settings.streamWaitingDelayMs()
                    ));
        }, settings.streamWaitingDelayMs(), TimeUnit.MILLISECONDS);

        replyFuture.whenComplete((reply, error) -> {
            waitingTask.cancel(false);
            String finalReply = error == null
                    ? reply
                    : ImReplySanitizer.FRIENDLY_IM_FALLBACK_REPLY;
            if (error != null) {
                LOGGER.log(Level.WARNING,
                        "DingTalk stream async reply failed, conversationHash=" + safeHash(conversationId),
                        error);
            }
            boolean sent = sendText(conversationId, finalReply);
            logEvent(sent ? Level.INFO : Level.WARNING,
                    sent ? "dingtalk.stream.final.sent" : "dingtalk.stream.final.failed",
                    Map.of(
                            "conversationHash", safeHash(conversationId),
                            "senderHash", safeHash(senderId),
                            "replyLength", safeLength(finalReply),
                            "hadWaitingStatus", waitingSent.get(),
                            "errorFallback", error != null
                    ));
            if (!sent && waitingSent.get()) {
                LOGGER.warning("DingTalk stream final reply could not be delivered after waiting status, conversationHash="
                        + safeHash(conversationId));
            }
        });
    }

    @PreDestroy
    void shutdown() {
        waitingScheduler.shutdownNow();
    }

    private boolean sendText(String conversationId, String reply) {
        String sanitized = ImReplySanitizer.sanitize(reply);
        rememberBotEcho(conversationId, sanitized);
        return conversationSender.sendText(conversationId, sanitized);
    }

    private boolean shouldSuppressAsBotEcho(String conversationId, String text) {
        pruneExpiredEchoes();
        String key = echoKey(conversationId, text);
        Long expiresAt = recentBotEchoes.get(key);
        if (expiresAt == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (expiresAt < now) {
            recentBotEchoes.remove(key, expiresAt);
            return false;
        }
        recentBotEchoes.remove(key, expiresAt);
        return true;
    }

    private void rememberBotEcho(String conversationId, String text) {
        if (conversationId == null || conversationId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        pruneExpiredEchoes();
        recentBotEchoes.put(echoKey(conversationId, text), System.currentTimeMillis() + ECHO_SUPPRESSION_TTL_MILLIS);
    }

    private void pruneExpiredEchoes() {
        if (recentBotEchoes.size() <= ECHO_SUPPRESSION_MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        recentBotEchoes.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private String nestedStringValue(Map<String, Object> payload, String parentKey, String childKey) {
        Object nested = payload.get(parentKey);
        if (!(nested instanceof Map<?, ?> nestedMap)) {
            return "";
        }
        Object value = nestedMap.get(childKey);
        return stringValue(value);
    }

    private Map<String, Object> nestedMapValue(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        rawMap.forEach((key, nestedValue) -> converted.put(String.valueOf(key), nestedValue));
        return converted;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString().trim();
        }
        if (value instanceof Map<?, ?> nestedMap && nestedMap.containsKey("content")) {
            Object content = nestedMap.get("content");
            return content == null ? "" : Objects.toString(content, "").trim();
        }
        return Objects.toString(value, "").trim();
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String echoKey(String conversationId, String text) {
        return conversationId.trim() + "\n" + text.trim();
    }

    private String safeHash(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return Integer.toHexString(value.trim().hashCode());
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private void logEvent(Level level, String eventName, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", eventName);
        if (fields != null) {
            event.putAll(fields);
        }
        try {
            LOGGER.log(level, objectMapper.writeValueAsString(event));
        } catch (Exception ex) {
            LOGGER.log(level, event.toString(), ex);
        }
    }

    Map<String, Object> emptyAck() {
        return new LinkedHashMap<>();
    }
}


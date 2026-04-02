package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
class DingtalkStreamMessageDispatcher {

    private static final Logger LOGGER = Logger.getLogger(DingtalkStreamMessageDispatcher.class.getName());
    private static final long ECHO_SUPPRESSION_TTL_MILLIS = 180_000L;
    private static final int ECHO_SUPPRESSION_MAX_ENTRIES = 256;
    private static final String TIMEOUT_BRIDGE_PREFIX = "感谢等待，以下是完整回复：\n";
    private static final long CARD_UPDATE_MIN_INTERVAL_MILLIS = 250L;
    private static final int CARD_UPDATE_MIN_DELTA_CHARS = 24;

    private final ImGatewayService imGatewayService;
    private final DingtalkConversationSender conversationSender;
    private final DingtalkIntegrationSettings settings;
    private final ScheduledExecutorService waitingScheduler;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, Long> recentBotEchoes = new ConcurrentHashMap<>();

    @Value("${mindos.im.dingtalk.reply-max-chars:1200}")
    private int dingtalkReplyMaxChars = 1200;

    @Value("${mindos.im.dingtalk.message.card.enabled:false}")
    private boolean dingtalkCardEnabled;

    @Value("${mindos.im.dingtalk.message.update.enabled:false}")
    private boolean dingtalkMessageUpdateEnabled;

    @Value("${mindos.im.dingtalk.agent-status.enabled:false}")
    private boolean dingtalkAgentStatusEnabled;

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
        String sessionWebhook = firstNonBlank(
                stringValue(payload.get("sessionWebhook")),
                stringValue(eventData.get("sessionWebhook"))
        );
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

        boolean streamCardUpdateEnabled = dingtalkCardEnabled && dingtalkMessageUpdateEnabled;
        AtomicReference<DingtalkMessageHandle> messageHandleRef = new AtomicReference<>();
        AtomicLong lastCardUpdateAtMillis = new AtomicLong(0L);
        AtomicInteger lastCardUpdateChars = new AtomicInteger(0);
        StringBuilder streamedReplyBuilder = new StringBuilder();
        CompletableFuture<String> replyFuture = streamCardUpdateEnabled
                ? imGatewayService.chatStream(ImPlatform.DINGTALK, senderId, conversationId, text, chunk -> {
                    if (chunk == null || chunk.isBlank()) {
                        return;
                    }
                    synchronized (streamedReplyBuilder) {
                        streamedReplyBuilder.append(chunk);
                    }
                    maybeUpdateStreamingCard(
                            conversationId,
                            sessionWebhook,
                            messageHandleRef.get(),
                            streamedReplyBuilder,
                            lastCardUpdateAtMillis,
                            lastCardUpdateChars
                    );
                })
                : imGatewayService.chatAsync(senderId, conversationId, text);
        AtomicBoolean waitingSent = new AtomicBoolean(false);
        AtomicBoolean timeoutObserved = new AtomicBoolean(false);
        ScheduledFuture<?> waitingTask = null;
        if (settings.streamForceWaiting()) {
            DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
            messageHandleRef.set(handle);
            waitingSent.set(handle != null && handle.sent());
            logEvent(waitingSent.get() ? Level.INFO : Level.WARNING,
                    waitingSent.get() ? "dingtalk.stream.waiting.sent" : "dingtalk.stream.waiting.failed",
                    Map.of(
                            "conversationHash", safeHash(conversationId),
                            "senderHash", safeHash(senderId),
                            "replyLength", settings.streamWaitingText().length(),
                            "waitingDelayMs", 0
                    ));
        } else {
            waitingTask = waitingScheduler.schedule(() -> {
                if (replyFuture.isDone()) {
                    return;
                }
                DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
                messageHandleRef.set(handle);
                waitingSent.set(handle != null && handle.sent());
                logEvent(waitingSent.get() ? Level.INFO : Level.WARNING,
                        waitingSent.get() ? "dingtalk.stream.waiting.sent" : "dingtalk.stream.waiting.failed",
                        Map.of(
                                "conversationHash", safeHash(conversationId),
                                "senderHash", safeHash(senderId),
                                "replyLength", settings.streamWaitingText().length(),
                                "waitingDelayMs", settings.streamWaitingDelayMs()
                        ));
            }, settings.streamWaitingDelayMs(), TimeUnit.MILLISECONDS);
        }

        ScheduledFuture<?> timeoutTask = waitingScheduler.schedule(() -> {
            if (replyFuture.isDone()) {
                return;
            }
            timeoutObserved.set(true);
            boolean hadWaitingStatus = waitingSent.get();
            if (hadWaitingStatus) {
                logEvent(Level.INFO, "dingtalk.stream.timeout.notice.skipped", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "senderHash", safeHash(senderId),
                        "timeoutMs", settings.streamFinalTimeoutMs(),
                        "hadWaitingStatus", true
                ));
                return;
            }
            DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
            messageHandleRef.set(handle);
            if (handle != null && handle.sent()) {
                waitingSent.set(true);
            }
            logEvent(waitingSent.get() ? Level.INFO : Level.WARNING,
                    waitingSent.get() ? "dingtalk.stream.timeout.notice.sent" : "dingtalk.stream.timeout.notice.failed",
                    Map.of(
                            "conversationHash", safeHash(conversationId),
                            "senderHash", safeHash(senderId),
                            "replyLength", settings.streamWaitingText().length(),
                            "timeoutMs", settings.streamFinalTimeoutMs(),
                            "hadWaitingStatus", hadWaitingStatus
                    ));
        }, settings.streamFinalTimeoutMs(), TimeUnit.MILLISECONDS);

        ScheduledFuture<?> finalWaitingTask = waitingTask;
        replyFuture.whenComplete((reply, error) -> {
            if (finalWaitingTask != null) {
                finalWaitingTask.cancel(false);
            }
            timeoutTask.cancel(false);
            String finalReply = error == null
                    ? reply
                    : ImReplySanitizer.FRIENDLY_IM_FALLBACK_REPLY;
            if (error == null && timeoutObserved.get()) {
                String normalizedFinal = finalReply == null ? "" : finalReply.trim();
                if (!normalizedFinal.isBlank() && !normalizedFinal.startsWith(TIMEOUT_BRIDGE_PREFIX.trim())) {
                    finalReply = TIMEOUT_BRIDGE_PREFIX + normalizedFinal;
                }
            }
            if (error != null) {
                LOGGER.log(Level.WARNING,
                        "DingTalk stream async reply failed, conversationHash=" + safeHash(conversationId),
                        error);
            }
            boolean sent = sendFinalStatus(conversationId, sessionWebhook, finalReply, waitingSent.get(), messageHandleRef.get());
            logEvent(sent ? Level.INFO : Level.WARNING,
                    sent ? "dingtalk.stream.final.sent" : "dingtalk.stream.final.failed",
                    Map.of(
                            "conversationHash", safeHash(conversationId),
                            "senderHash", safeHash(senderId),
                            "replyLength", safeLength(finalReply),
                            "hadWaitingStatus", waitingSent.get(),
                            "errorFallback", error != null,
                            "timeoutObserved", timeoutObserved.get()
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

    private boolean sendText(String conversationId, String reply, String sessionWebhook) {
        String sanitized = ImReplySanitizer.sanitize(reply);
        boolean sentAll = true;
        for (String segment : splitForDingtalk(sanitized)) {
            boolean sent = conversationSender.sendText(conversationId, segment, sessionWebhook);
            if (sent) {
                rememberBotEcho(conversationId, segment);
                continue;
            }
            sentAll = false;
            break;
        }
        return sentAll;
    }

    private DingtalkMessageHandle sendWaitingStatus(String conversationId, String sessionWebhook, String waitingText) {
        if (dingtalkAgentStatusEnabled) {
            logEvent(Level.INFO, "dingtalk.stream.agent.status", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "status", "processing"
            ));
        }
        if (dingtalkCardEnabled) {
            String card = buildStatusCardMarkdown("处理中", waitingText);
            String preferredWebhook = dingtalkMessageUpdateEnabled ? "" : sessionWebhook;
            DingtalkMessageHandle handle = conversationSender.sendMarkdownCardHandle(conversationId, "MindOS 处理中", card, preferredWebhook);
            if (handle != null && handle.sent()) {
                rememberBotEcho(conversationId, card);
                return handle;
            }
        }
        boolean sent = sendText(conversationId, waitingText, sessionWebhook);
        return sent
                ? DingtalkMessageHandle.sentWithoutUpdate(conversationId, sessionWebhook)
                : DingtalkMessageHandle.notSent(conversationId, sessionWebhook);
    }

    private boolean sendFinalStatus(String conversationId,
                                    String sessionWebhook,
                                    String finalReply,
                                    boolean hadWaitingStatus,
                                    DingtalkMessageHandle handle) {
        if (dingtalkAgentStatusEnabled) {
            logEvent(Level.INFO, "dingtalk.stream.agent.status", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "status", "completed"
            ));
        }
        if (dingtalkCardEnabled) {
            String card = buildStatusCardMarkdown("已完成", finalReply);
            boolean sent = hadWaitingStatus && dingtalkMessageUpdateEnabled && handle != null
                    ? conversationSender.updateMessage(handle, "MindOS 完整回复", card, sessionWebhook)
                    : conversationSender.sendMarkdownCard(conversationId, "MindOS 完整回复", card, sessionWebhook);
            if (sent) {
                rememberBotEcho(conversationId, card);
                return true;
            }
        }
        if (hadWaitingStatus && dingtalkMessageUpdateEnabled) {
            return sendText(conversationId, "【更新】\n" + (finalReply == null ? "" : finalReply), sessionWebhook);
        }
        return sendText(conversationId, finalReply, sessionWebhook);
    }

    private void maybeUpdateStreamingCard(String conversationId,
                                          String sessionWebhook,
                                          DingtalkMessageHandle handle,
                                          StringBuilder streamedReplyBuilder,
                                          AtomicLong lastCardUpdateAtMillis,
                                          AtomicInteger lastCardUpdateChars) {
        if (!dingtalkCardEnabled || !dingtalkMessageUpdateEnabled || handle == null || !handle.canUpdate()) {
            return;
        }
        String snapshot;
        synchronized (streamedReplyBuilder) {
            snapshot = streamedReplyBuilder.toString().trim();
        }
        if (snapshot.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        int currentLength = snapshot.length();
        if (now - lastCardUpdateAtMillis.get() < CARD_UPDATE_MIN_INTERVAL_MILLIS
                && currentLength - lastCardUpdateChars.get() < CARD_UPDATE_MIN_DELTA_CHARS) {
            return;
        }
        String card = buildStatusCardMarkdown("处理中", snapshot + "\n\n_持续生成中…_");
        if (conversationSender.updateMessage(handle, "MindOS 处理中", card, sessionWebhook)) {
            rememberBotEcho(conversationId, card);
            lastCardUpdateAtMillis.set(now);
            lastCardUpdateChars.set(currentLength);
            logEvent(Level.INFO, "dingtalk.stream.card.updated", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "replyLength", currentLength
            ));
        }
    }

    private String buildStatusCardMarkdown(String status, String body) {
        String normalizedBody = body == null ? "" : body.trim();
        StringBuilder markdown = new StringBuilder();
        markdown.append("### MindOS Agent\n\n");
        markdown.append("- 状态：**").append(status).append("**\n");
        markdown.append("- 渠道：DingTalk Stream\n\n");
        markdown.append(normalizedBody.isBlank() ? "(暂无文本内容)" : normalizedBody);
        return markdown.toString();
    }

    private java.util.List<String> splitForDingtalk(String reply) {
        if (reply == null || reply.isEmpty()) {
            return java.util.List.of("");
        }
        int maxChars = Math.max(200, dingtalkReplyMaxChars);
        if (reply.length() <= maxChars) {
            return java.util.List.of(reply);
        }
        java.util.List<String> segments = new java.util.ArrayList<>();
        for (int start = 0; start < reply.length(); start += maxChars) {
            int end = Math.min(reply.length(), start + maxChars);
            segments.add(reply.substring(start, end));
        }
        return java.util.List.copyOf(segments);
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


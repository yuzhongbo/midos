package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
    private static final int STREAM_STATS_MAX_SESSION_ENTRIES = 512;

    private final ImGatewayService imGatewayService;
    private final DingtalkConversationSender conversationSender;
    private final DingtalkIntegrationSettings settings;
    private final ScheduledExecutorService waitingScheduler;
    private final ExecutorService cardUpdateExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, Long> recentBotEchoes = new ConcurrentHashMap<>();
    private final AtomicLong firstStatusLatencyTotalMs = new AtomicLong(0L);
    private final AtomicLong firstStatusLatencyCount = new AtomicLong(0L);
    private final AtomicLong firstTokenLatencyTotalMs = new AtomicLong(0L);
    private final AtomicLong firstTokenLatencyCount = new AtomicLong(0L);
    private final AtomicLong waitingDecisionSentCount = new AtomicLong(0L);
    private final AtomicLong waitingDecisionSuppressedCount = new AtomicLong(0L);
    private final Map<String, Integer> sessionCardUpdateCounts = new ConcurrentHashMap<>();

    @Value("${mindos.im.dingtalk.reply-max-chars:1200}")
    private int dingtalkReplyMaxChars = 1200;

    @Value("${mindos.im.dingtalk.message.card.enabled:false}")
    private boolean dingtalkCardEnabled;

    @Value("${mindos.im.dingtalk.message.update.enabled:false}")
    private boolean dingtalkMessageUpdateEnabled;

    @Value("${mindos.im.dingtalk.agent-status.enabled:false}")
    private boolean dingtalkAgentStatusEnabled;

    @Value("${mindos.im.dingtalk.card.update.min-interval-ms:250}")
    private long cardUpdateMinIntervalMillis = 250L;

    @Value("${mindos.im.dingtalk.card.update.min-delta-chars:24}")
    private int cardUpdateMinDeltaChars = 24;

    @Value("${mindos.im.dingtalk.stream.waiting.smart-enabled:true}")
    private boolean smartWaitingEnabled = true;

    @Value("${mindos.im.dingtalk.stream.waiting.smart.min-input-chars:12}")
    private int smartWaitingMinInputChars = 12;

    @Value("${mindos.im.dingtalk.stream.waiting.smart.keywords:搜索,查一下,查下,新闻,最新,实时,总结,总结一下,分析,解释,方案,规划,计划,代码,修复,排查,生成,compare,analysis,debug,search,latest,news,report,plan}")
    private String smartWaitingKeywords = "搜索,查一下,查下,新闻,最新,实时,总结,总结一下,分析,解释,方案,规划,计划,代码,修复,排查,生成,compare,analysis,debug,search,latest,news,report,plan";

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
        this.cardUpdateExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mindos-dingtalk-card-update");
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
        WaitingDecision waitingDecision = decideWaitingStrategy(text, streamCardUpdateEnabled);
        long receivedAtMillis = System.currentTimeMillis();
        AtomicBoolean firstStatusRecorded = new AtomicBoolean(false);
        AtomicBoolean firstTokenRecorded = new AtomicBoolean(false);
        AtomicReference<DingtalkMessageHandle> messageHandleRef = new AtomicReference<>();
        AtomicLong lastCardUpdateAtMillis = new AtomicLong(0L);
        AtomicInteger lastCardUpdateChars = new AtomicInteger(0);
        AtomicReference<String> pendingCardSnapshot = new AtomicReference<>("");
        AtomicBoolean cardUpdateInFlight = new AtomicBoolean(false);
        AtomicBoolean waitingSent = new AtomicBoolean(false);
        AtomicBoolean waitingAttempted = new AtomicBoolean(false);
        AtomicBoolean timeoutObserved = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> waitingTaskRef = new AtomicReference<>();
        StringBuilder streamedReplyBuilder = new StringBuilder();
        CompletableFuture<String> replyFuture = streamCardUpdateEnabled
                ? imGatewayService.chatStream(ImPlatform.DINGTALK, senderId, conversationId, text, chunk -> {
                    if (chunk == null || chunk.isBlank()) {
                        return;
                    }
                    recordFirstTokenLatency(receivedAtMillis, firstTokenRecorded);
                    if (waitingDecision.immediate() && waitingAttempted.compareAndSet(false, true)) {
                        ScheduledFuture<?> waitingTask = waitingTaskRef.get();
                        if (waitingTask != null) {
                            waitingTask.cancel(false);
                        }
                        DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
                        messageHandleRef.set(handle);
                        waitingSent.set(handle != null && handle.sent());
                        if (waitingSent.get()) {
                            recordFirstStatusLatency(receivedAtMillis, firstStatusRecorded);
                        }
                        logEvent(waitingSent.get() ? Level.INFO : Level.WARNING,
                                waitingSent.get() ? "dingtalk.stream.waiting.fast-track.sent" : "dingtalk.stream.waiting.fast-track.failed",
                                Map.of(
                                        "conversationHash", safeHash(conversationId),
                                        "senderHash", safeHash(senderId),
                                        "replyLength", settings.streamWaitingText().length(),
                                        "waitingDelayMs", 0
                                ));
                    }
                    synchronized (streamedReplyBuilder) {
                        streamedReplyBuilder.append(chunk);
                    }
                    maybeQueueStreamingCardUpdate(
                            conversationId,
                            sessionWebhook,
                            messageHandleRef,
                            streamedReplyBuilder,
                            lastCardUpdateAtMillis,
                            lastCardUpdateChars,
                            pendingCardSnapshot,
                            cardUpdateInFlight
                    );
                })
                : imGatewayService.chatAsync(senderId, conversationId, text);
        ScheduledFuture<?> waitingTask = null;
        if (waitingDecision.immediate()) {
            if (waitingAttempted.compareAndSet(false, true)) {
                DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
                messageHandleRef.set(handle);
                waitingSent.set(handle != null && handle.sent());
                if (waitingSent.get()) {
                    recordFirstStatusLatency(receivedAtMillis, firstStatusRecorded);
                }
                logEvent(waitingSent.get() ? Level.INFO : Level.WARNING,
                        waitingSent.get() ? "dingtalk.stream.waiting.sent" : "dingtalk.stream.waiting.failed",
                        Map.of(
                                "conversationHash", safeHash(conversationId),
                                "senderHash", safeHash(senderId),
                                "replyLength", settings.streamWaitingText().length(),
                                "waitingDelayMs", 0,
                                "waitingReason", waitingDecision.reason()
                        ));
            }
        } else if (waitingDecision.delayed()) {
            waitingTask = waitingScheduler.schedule(() -> {
                if (replyFuture.isDone()) {
                    return;
                }
                if (!waitingAttempted.compareAndSet(false, true)) {
                    return;
                }
                DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
                messageHandleRef.set(handle);
                waitingSent.set(handle != null && handle.sent());
                if (waitingSent.get()) {
                    recordFirstStatusLatency(receivedAtMillis, firstStatusRecorded);
                }
                logEvent(waitingSent.get() ? Level.INFO : Level.WARNING,
                        waitingSent.get() ? "dingtalk.stream.waiting.sent" : "dingtalk.stream.waiting.failed",
                        Map.of(
                                "conversationHash", safeHash(conversationId),
                                "senderHash", safeHash(senderId),
                                "replyLength", settings.streamWaitingText().length(),
                                "waitingDelayMs", waitingDecision.delayMs(),
                                "waitingReason", waitingDecision.reason()
                        ));
            }, waitingDecision.delayMs(), TimeUnit.MILLISECONDS);
        } else {
            logEvent(Level.INFO, "dingtalk.stream.waiting.skipped", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "senderHash", safeHash(senderId),
                    "textLength", text.length(),
                    "waitingReason", waitingDecision.reason(),
                    "smartWaitingEnabled", smartWaitingEnabled,
                    "forceWaiting", settings.streamForceWaiting()
            ));
        }
        waitingTaskRef.set(waitingTask);

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
            waitingAttempted.set(true);
            DingtalkMessageHandle handle = sendWaitingStatus(conversationId, sessionWebhook, settings.streamWaitingText());
            messageHandleRef.set(handle);
            if (handle != null && handle.sent()) {
                waitingSent.set(true);
                recordFirstStatusLatency(receivedAtMillis, firstStatusRecorded);
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
            waitingTaskRef.set(null);
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
            finalReply = ImReplySanitizer.sanitize(finalReply);
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
        cardUpdateExecutor.shutdownNow();
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
            return sendText(conversationId, finalReply, sessionWebhook);
        }
        return sendText(conversationId, finalReply, sessionWebhook);
    }

    private void maybeQueueStreamingCardUpdate(String conversationId,
                                               String sessionWebhook,
                                               AtomicReference<DingtalkMessageHandle> handleRef,
                                               StringBuilder streamedReplyBuilder,
                                               AtomicLong lastCardUpdateAtMillis,
                                               AtomicInteger lastCardUpdateChars,
                                               AtomicReference<String> pendingCardSnapshot,
                                               AtomicBoolean cardUpdateInFlight) {
        if (!dingtalkCardEnabled || !dingtalkMessageUpdateEnabled) {
            return;
        }
        DingtalkMessageHandle currentHandle = handleRef.get();
        if (currentHandle == null || !currentHandle.canUpdate()) {
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
        if (now - lastCardUpdateAtMillis.get() < Math.max(0L, cardUpdateMinIntervalMillis)
                && currentLength - lastCardUpdateChars.get() < Math.max(1, cardUpdateMinDeltaChars)) {
            return;
        }
        pendingCardSnapshot.set(snapshot);
        if (!cardUpdateInFlight.compareAndSet(false, true)) {
            return;
        }
        cardUpdateExecutor.execute(() -> flushPendingCardUpdates(
                conversationId,
                sessionWebhook,
                handleRef,
                pendingCardSnapshot,
                cardUpdateInFlight,
                lastCardUpdateAtMillis,
                lastCardUpdateChars
        ));
    }

    private void flushPendingCardUpdates(String conversationId,
                                         String sessionWebhook,
                                         AtomicReference<DingtalkMessageHandle> handleRef,
                                         AtomicReference<String> pendingCardSnapshot,
                                         AtomicBoolean cardUpdateInFlight,
                                         AtomicLong lastCardUpdateAtMillis,
                                         AtomicInteger lastCardUpdateChars) {
        try {
            while (true) {
                String snapshot = pendingCardSnapshot.getAndSet("").trim();
                if (snapshot.isBlank()) {
                    return;
                }
                DingtalkMessageHandle handle = handleRef.get();
                if (handle == null || !handle.canUpdate()) {
                    continue;
                }
                String card = buildStatusCardMarkdown("处理中", snapshot + "\n\n_持续生成中…_");
                if (conversationSender.updateMessage(handle, "MindOS 处理中", card, sessionWebhook)) {
                    rememberBotEcho(conversationId, card);
                    long now = System.currentTimeMillis();
                    lastCardUpdateAtMillis.set(now);
                    lastCardUpdateChars.set(snapshot.length());
                    logEvent(Level.INFO, "dingtalk.stream.card.updated", Map.of(
                            "conversationHash", safeHash(conversationId),
                            "replyLength", snapshot.length()
                    ));
                    sessionCardUpdateCounts.merge(safeHash(conversationId), 1, Integer::sum);
                    trimSessionStatsIfNeeded();
                }
            }
        } finally {
            cardUpdateInFlight.set(false);
            if (!pendingCardSnapshot.get().isBlank() && cardUpdateInFlight.compareAndSet(false, true)) {
                cardUpdateExecutor.execute(() -> flushPendingCardUpdates(
                        conversationId,
                        sessionWebhook,
                        handleRef,
                        pendingCardSnapshot,
                        cardUpdateInFlight,
                        lastCardUpdateAtMillis,
                        lastCardUpdateChars
                ));
            }
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

    Map<String, Object> streamStatsSnapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long statusCount = firstStatusLatencyCount.get();
        long statusTotal = firstStatusLatencyTotalMs.get();
        long tokenCount = firstTokenLatencyCount.get();
        long tokenTotal = firstTokenLatencyTotalMs.get();
        stats.put("avgFirstStatusLatencyMs", averageMillis(statusTotal, statusCount));
        stats.put("avgFirstTokenLatencyMs", averageMillis(tokenTotal, tokenCount));
        stats.put("firstStatusSamples", statusCount);
        stats.put("firstTokenSamples", tokenCount);
        stats.put("waitingSentDecisions", waitingDecisionSentCount.get());
        stats.put("waitingSuppressedDecisions", waitingDecisionSuppressedCount.get());
        stats.put("cardUpdatesPerConversation", Map.copyOf(new LinkedHashMap<>(sessionCardUpdateCounts)));
        return Map.copyOf(stats);
    }

    private WaitingDecision decideWaitingStrategy(String text, boolean streamCardUpdateEnabled) {
        if (settings.streamForceWaiting()) {
            waitingDecisionSentCount.incrementAndGet();
            return new WaitingDecision(true, false, 0L, "force_waiting");
        }
        long baseDelayMs = Math.max(1L, settings.streamWaitingDelayMs());
        if (!smartWaitingEnabled) {
            waitingDecisionSentCount.incrementAndGet();
            return new WaitingDecision(false, true, baseDelayMs, "smart_waiting_disabled");
        }
        String normalized = stringValue(text).toLowerCase();
        if (normalized.length() >= Math.max(1, smartWaitingMinInputChars)) {
            waitingDecisionSentCount.incrementAndGet();
            return new WaitingDecision(false, true, baseDelayMs, "input_length");
        }
        for (String keyword : parseSmartWaitingKeywords()) {
            if (!keyword.isBlank() && normalized.contains(keyword)) {
                waitingDecisionSentCount.incrementAndGet();
                long keywordDelayMs = baseDelayMs;
                if ("新闻".equals(keyword) || "news".equals(keyword) || "最新".equals(keyword) || "latest".equals(keyword) || "搜索".equals(keyword) || "search".equals(keyword) || "实时".equals(keyword)) {
                    keywordDelayMs = Math.max(baseDelayMs, 2200L);
                }
                return new WaitingDecision(false, true, keywordDelayMs, "keyword:" + keyword);
            }
        }
        if (streamCardUpdateEnabled && (normalized.contains("进展") || normalized.contains("继续") || normalized.contains("过程") || normalized.contains("stream"))) {
            waitingDecisionSentCount.incrementAndGet();
            return new WaitingDecision(false, true, Math.max(baseDelayMs, 1500L), "stream_card_progress");
        }
        waitingDecisionSuppressedCount.incrementAndGet();
        return new WaitingDecision(false, false, 0L, "light_request");
    }

    private Set<String> parseSmartWaitingKeywords() {
        Set<String> keywords = new LinkedHashSet<>();
        String configured = smartWaitingKeywords == null ? "" : smartWaitingKeywords;
        for (String raw : configured.split(",")) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase();
            if (!normalized.isBlank()) {
                keywords.add(normalized);
            }
        }
        return keywords;
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

    private void recordFirstStatusLatency(long receivedAtMillis, AtomicBoolean recordedFlag) {
        if (!recordedFlag.compareAndSet(false, true)) {
            return;
        }
        long latency = Math.max(0L, System.currentTimeMillis() - receivedAtMillis);
        firstStatusLatencyTotalMs.addAndGet(latency);
        firstStatusLatencyCount.incrementAndGet();
    }

    private void recordFirstTokenLatency(long receivedAtMillis, AtomicBoolean recordedFlag) {
        if (!recordedFlag.compareAndSet(false, true)) {
            return;
        }
        long latency = Math.max(0L, System.currentTimeMillis() - receivedAtMillis);
        firstTokenLatencyTotalMs.addAndGet(latency);
        firstTokenLatencyCount.incrementAndGet();
    }

    private double averageMillis(long total, long count) {
        if (count <= 0L) {
            return 0.0d;
        }
        return Math.round((total * 10.0d) / count) / 10.0d;
    }

    private void trimSessionStatsIfNeeded() {
        if (sessionCardUpdateCounts.size() <= STREAM_STATS_MAX_SESSION_ENTRIES) {
            return;
        }
        String keyToRemove = sessionCardUpdateCounts.keySet().stream().findFirst().orElse("");
        if (!keyToRemove.isBlank()) {
            sessionCardUpdateCounts.remove(keyToRemove);
        }
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

    private record WaitingDecision(boolean immediate, boolean delayed, long delayMs, String reason) {
    }
}


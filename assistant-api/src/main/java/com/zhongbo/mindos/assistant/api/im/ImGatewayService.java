package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.nlu.MemoryIntentNlu;
import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.memory.MemoryConsolidationService;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImGatewayService {

    private static final Logger LOGGER = Logger.getLogger(ImGatewayService.class.getName());

    private static final String PROP_TODO_P1_THRESHOLD = "mindos.todo.priority.p1-threshold";
    private static final String PROP_TODO_P2_THRESHOLD = "mindos.todo.priority.p2-threshold";
    private static final String PROP_TODO_WINDOW_P1 = "mindos.todo.window.p1";
    private static final String PROP_TODO_WINDOW_P2 = "mindos.todo.window.p2";
    private static final String PROP_TODO_WINDOW_P3 = "mindos.todo.window.p3";
    private static final String PROP_TODO_LEGEND = "mindos.todo.legend";
    private static final String DINGTALK_ASYNC_TASK_TITLE = "钉钉消息处理";
    private static final List<String> DINGTALK_ASYNC_STEPS = List.of("等待后台处理", "生成回复", "回推钉钉结果");
    private static final List<String> DEFAULT_DINGTALK_ALLOWED_HOSTS = List.of(
            "localhost",
            "127.0.0.1",
            ".dingtalk.com",
            ".dingtalkapps.com"
    );
    private static final int DEFAULT_DINGTALK_CONNECT_TIMEOUT_MS = 3000;
    private static final int DEFAULT_DINGTALK_REQUEST_TIMEOUT_MS = 10000;
    private static final long DEFAULT_DINGTALK_EXPIRY_SKEW_SECONDS = 5L;
    private static final int DEFAULT_DINGTALK_EXECUTOR_THREADS = 2;
    private static final String[] DINGTALK_PROGRESS_QUERY_TERMS = {
            "查进度", "查看进度", "进度", "任务进度", "查看状态", "查状态", "进度查询", "status"
    };
    private static final String[] DINGTALK_RESULT_QUERY_TERMS = {
            "查结果", "查看结果", "结果", "任务结果", "最终结果", "处理结果", "结果查询"
    };
    private static final String DINGTALK_RESULT_NOTE_PREFIX = "[ASYNC_RESULT] ";
    private static final String DINGTALK_COMPENSATED_NOTE_PREFIX = "[COMPENSATED] ";
    private static final Pattern TASK_ID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final int MAX_COMPENSATION_RESULTS = 2;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.zhongbo.mindos.assistant.dispatcher.DispatcherFacade dispatcherService;
    private final MemoryFacade memoryFacade;
    private final MemoryConsolidationService memoryConsolidationService;
    private final DingtalkAsyncReplyClient dingtalkAsyncReplyClient;
    private final DingtalkOpenApiMessageClient dingtalkOpenApiMessageClient;
    private final ExecutorService dingtalkAsyncExecutor;
    private final boolean dingtalkAsyncReplyEnabled;
    private final long dingtalkAsyncReplyExpirySkewSeconds;
    private final String dingtalkAsyncAcceptedTemplate;
    private final String dingtalkAsyncResultPrefix;
    private final AtomicInteger dingtalkAsyncThreadCounter = new AtomicInteger(1);
    private final Map<String, String> pendingKeyPointReviews = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pendingTodoFromReview = new ConcurrentHashMap<>();
    private final Map<String, DingtalkAsyncTaskContext> pendingDingtalkReplies = new ConcurrentHashMap<>();

    ImGatewayService(com.zhongbo.mindos.assistant.dispatcher.DispatcherFacade dispatcherService,
                     MemoryFacade memoryFacade,
                     MemoryConsolidationService memoryConsolidationService) {
        this(dispatcherService,
                memoryFacade,
                memoryConsolidationService,
                new DingtalkAsyncReplyClient(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofMillis(DEFAULT_DINGTALK_CONNECT_TIMEOUT_MS))
                                .build(),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                        java.time.Duration.ofMillis(DEFAULT_DINGTALK_REQUEST_TIMEOUT_MS),
                        DEFAULT_DINGTALK_ALLOWED_HOSTS,
                        true),
                new DingtalkOpenApiMessageClient(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofMillis(DEFAULT_DINGTALK_CONNECT_TIMEOUT_MS))
                                .build(),
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                        java.time.Duration.ofMillis(DEFAULT_DINGTALK_REQUEST_TIMEOUT_MS),
                        false,
                        "",
                        "",
                        "",
                        "https://api.dingtalk.com/v1.0/oauth2/accessToken",
                        "https://api.dingtalk.com/v1.0/im/messages/sendToConversation",
                        "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend",
                        DingtalkOpenApiMessageClient.SendMode.CONVERSATION_FIRST,
                        60L,
                        DEFAULT_DINGTALK_ALLOWED_HOSTS,
                        true),
                true,
                DEFAULT_DINGTALK_EXECUTOR_THREADS,
                DEFAULT_DINGTALK_EXPIRY_SKEW_SECONDS,
                "已收到，正在处理中。任务ID：%s。完成后我会把完整结果发回当前会话。",
                "处理完成，以下是完整结果：");
    }

    ImGatewayService(com.zhongbo.mindos.assistant.dispatcher.DispatcherFacade dispatcherService,
                     MemoryFacade memoryFacade,
                     MemoryConsolidationService memoryConsolidationService,
                     DingtalkAsyncReplyClient dingtalkAsyncReplyClient,
                     DingtalkOpenApiMessageClient dingtalkOpenApiMessageClient) {
        this(dispatcherService,
                memoryFacade,
                memoryConsolidationService,
                dingtalkAsyncReplyClient,
                dingtalkOpenApiMessageClient,
                true,
                DEFAULT_DINGTALK_EXECUTOR_THREADS,
                DEFAULT_DINGTALK_EXPIRY_SKEW_SECONDS,
                "已收到，正在处理中。任务ID：%s。完成后我会把完整结果发回当前会话。",
                "处理完成，以下是完整结果：");
    }

    @Autowired
    ImGatewayService(com.zhongbo.mindos.assistant.dispatcher.DispatcherFacade dispatcherService,
                     MemoryFacade memoryFacade,
                     MemoryConsolidationService memoryConsolidationService,
                     DingtalkAsyncReplyClient dingtalkAsyncReplyClient,
                     DingtalkOpenApiMessageClient dingtalkOpenApiMessageClient,
                     @Value("${mindos.im.dingtalk.async-reply.enabled:true}") boolean dingtalkAsyncReplyEnabled,
                     @Value("${mindos.im.dingtalk.async-reply.executor-threads:2}") int dingtalkAsyncExecutorThreads,
                      @Value("${mindos.im.dingtalk.async-reply.expiry-skew-seconds:5}") long dingtalkAsyncReplyExpirySkewSeconds,
                      @Value("${mindos.im.dingtalk.async-reply.accepted-template:已收到，正在处理中。任务ID：%s。完成后我会把完整结果发回当前会话。}") String dingtalkAsyncAcceptedTemplate,
                      @Value("${mindos.im.dingtalk.async-reply.result-prefix:处理完成，以下是完整结果：}") String dingtalkAsyncResultPrefix) {
        this.dispatcherService = dispatcherService;
        this.memoryFacade = memoryFacade;
        this.memoryConsolidationService = memoryConsolidationService;
        this.dingtalkAsyncReplyClient = dingtalkAsyncReplyClient;
        this.dingtalkOpenApiMessageClient = dingtalkOpenApiMessageClient;
        this.dingtalkAsyncReplyEnabled = dingtalkAsyncReplyEnabled;
        this.dingtalkAsyncReplyExpirySkewSeconds = Math.max(0L, dingtalkAsyncReplyExpirySkewSeconds);
        this.dingtalkAsyncAcceptedTemplate = dingtalkAsyncAcceptedTemplate == null || dingtalkAsyncAcceptedTemplate.isBlank()
                ? "已收到，正在处理中。任务ID：%s。完成后我会把完整结果发回当前会话。"
                : dingtalkAsyncAcceptedTemplate;
        this.dingtalkAsyncResultPrefix = dingtalkAsyncResultPrefix == null || dingtalkAsyncResultPrefix.isBlank()
                ? "处理完成，以下是完整结果："
                : dingtalkAsyncResultPrefix;
        this.dingtalkAsyncExecutor = Executors.newFixedThreadPool(Math.max(1, dingtalkAsyncExecutorThreads), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("mindos-dingtalk-async-" + dingtalkAsyncThreadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    String chat(ImPlatform platform, String senderId, String chatId, String text) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            return "请发送文本消息，我会继续协助你。";
        }
        return buildReply(platform, senderId, chatId, normalizedText);
    }

    CompletableFuture<String> chatAsync(String senderId, String chatId, String text) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            return CompletableFuture.completedFuture("请发送文本消息，我会继续协助你。");
        }
        return CompletableFuture.supplyAsync(
                () -> buildReply(ImPlatform.DINGTALK, senderId, chatId, normalizedText),
                dingtalkAsyncExecutor
        );
    }

    CompletableFuture<String> chatStream(ImPlatform platform,
                                         String senderId,
                                         String chatId,
                                         String text,
                                         Consumer<String> deltaConsumer) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            String reply = "请发送文本消息，我会继续协助你。";
            if (deltaConsumer != null) {
                deltaConsumer.accept(reply);
            }
            return CompletableFuture.completedFuture(reply);
        }

        String userId = buildUserId(platform, senderId);
        String asyncTaskReply = tryHandleDingtalkAsyncTaskIntent(platform, userId, normalizedText);
        if (asyncTaskReply != null) {
            String reply = sanitizeAndObserve(platform, senderId, chatId, userId, "async-task", asyncTaskReply);
            if (deltaConsumer != null) {
                deltaConsumer.accept(reply);
            }
            return CompletableFuture.completedFuture(reply);
        }

        String memoryReply = tryHandleMemoryPlanningIntent(userId, normalizedText);
        if (memoryReply != null) {
            String reply = sanitizeAndObserve(platform, senderId, chatId, userId, "memory-intent", memoryReply);
            if (deltaConsumer != null) {
                deltaConsumer.accept(reply);
            }
            return CompletableFuture.completedFuture(reply);
        }

        Map<String, Object> profileContext = buildProfileContext(platform, senderId, chatId);
        return dispatcherService.dispatchStream(userId, normalizedText, profileContext, deltaConsumer)
                .thenApply(result -> {
                    String replySource = result.channel() == null || result.channel().isBlank()
                            ? "dispatcher"
                            : "dispatcher:" + result.channel().trim();
                    String reply = sanitizeAndObserve(platform, senderId, chatId, userId, replySource, result.reply());
                    String compensationNotice = buildPendingCompensationNotice(platform, userId);
                    if (compensationNotice == null || compensationNotice.isBlank()) {
                        return reply;
                    }
                    return compensationNotice + "\n\n" + reply;
                });
    }

    AsyncReplyAck startDingtalkAsyncReply(String senderId,
                                          String chatId,
                                          String text,
                                          String sessionWebhook,
                                          Long sessionWebhookExpiredTime) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            return null;
        }
        if (!dingtalkAsyncReplyEnabled || !dingtalkAsyncReplyClient.isUsableSessionWebhook(sessionWebhook)) {
            return null;
        }
        Instant expiresAt = resolveSessionWebhookExpiry(sessionWebhookExpiredTime);
        if (!isWebhookUsable(expiresAt)) {
            return null;
        }
        String userId = buildUserId(ImPlatform.DINGTALK, senderId);
        LongTask task = memoryFacade.createLongTask(
                userId,
                DINGTALK_ASYNC_TASK_TITLE,
                normalizedText,
                DINGTALK_ASYNC_STEPS,
                null,
                Instant.now()
        );
        DingtalkAsyncTaskContext context = new DingtalkAsyncTaskContext(
                task.taskId(),
                userId,
                senderId == null ? "" : senderId,
                chatId == null ? "" : chatId,
                normalizedText,
                sessionWebhook.trim(),
                expiresAt
        );
        pendingDingtalkReplies.put(task.taskId(), context);
        dingtalkAsyncExecutor.submit(() -> processDingtalkAsyncReply(context));
        return new AsyncReplyAck(task.taskId(), String.format(dingtalkAsyncAcceptedTemplate, task.taskId()));
    }

    @PreDestroy
    void shutdownAsyncExecutor() {
        dingtalkAsyncExecutor.shutdown();
        try {
            if (!dingtalkAsyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dingtalkAsyncExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dingtalkAsyncExecutor.shutdownNow();
        }
    }

    private void processDingtalkAsyncReply(DingtalkAsyncTaskContext context) {
        memoryFacade.updateLongTaskProgress(
                context.userId(),
                context.taskId(),
                "im-dingtalk-async",
                "等待后台处理",
                "已开始处理钉钉消息",
                "",
                Instant.now(),
                false
        );
        try {
            String reply = buildReply(ImPlatform.DINGTALK, context.senderId(), context.chatId(), context.inputText());
            memoryFacade.updateLongTaskProgress(
                    context.userId(),
                    context.taskId(),
                    "im-dingtalk-async",
                    "生成回复",
                    DINGTALK_RESULT_NOTE_PREFIX + reply,
                    "",
                    Instant.now(),
                    false
            );
            if (!isWebhookUsable(context.sessionWebhookExpiresAt())) {
                if (tryPushViaDingtalkOpenApi(context, reply)) {
                    return;
                }
                memoryFacade.updateLongTaskProgress(
                        context.userId(),
                        context.taskId(),
                        "im-dingtalk-async",
                        null,
                        "钉钉 sessionWebhook 已过期，未能回推最终结果",
                        "钉钉 sessionWebhook 已过期，未能回推最终结果",
                        Instant.now(),
                        false
                );
                return;
            }
            boolean sent = dingtalkAsyncReplyClient.sendText(context.sessionWebhook(), formatAsyncResult(reply));
            if (sent) {
                memoryFacade.updateLongTaskProgress(
                        context.userId(),
                        context.taskId(),
                        "im-dingtalk-async",
                        "回推钉钉结果",
                        "已将结果回推到钉钉会话",
                        "",
                        Instant.now(),
                        true
                );
            } else {
                if (tryPushViaDingtalkOpenApi(context, reply)) {
                    return;
                }
                memoryFacade.updateLongTaskProgress(
                        context.userId(),
                        context.taskId(),
                        "im-dingtalk-async",
                        null,
                        "钉钉异步回推失败，请稍后重试或通过任务接口查看结果",
                        "钉钉异步回推失败，请稍后重试或通过任务接口查看结果",
                        Instant.now(),
                        false
                );
            }
        } catch (Exception ex) {
            memoryFacade.updateLongTaskProgress(
                    context.userId(),
                    context.taskId(),
                    "im-dingtalk-async",
                    null,
                    "钉钉消息处理失败: " + ex.getMessage(),
                    "钉钉消息处理失败: " + ex.getMessage(),
                    Instant.now(),
                    false
            );
        } finally {
            pendingDingtalkReplies.remove(context.taskId());
        }
    }

    private String buildReply(ImPlatform platform, String senderId, String chatId, String normalizedText) {
        String userId = buildUserId(platform, senderId);
        Map<String, Object> profileContext = buildProfileContext(platform, senderId, chatId);

        String asyncTaskReply = tryHandleDingtalkAsyncTaskIntent(platform, userId, normalizedText);
        if (asyncTaskReply != null) {
            return sanitizeAndObserve(platform, senderId, chatId, userId, "async-task", asyncTaskReply);
        }

        String memoryReply = tryHandleMemoryPlanningIntent(userId, normalizedText);
        if (memoryReply != null) {
            return sanitizeAndObserve(platform, senderId, chatId, userId, "memory-intent", memoryReply);
        }

        DispatchResult result = dispatcherService.dispatch(userId, normalizedText, profileContext);
        String replySource = result.channel() == null || result.channel().isBlank()
                ? "dispatcher"
                : "dispatcher:" + result.channel().trim();
        String reply = sanitizeAndObserve(platform, senderId, chatId, userId, replySource, result.reply());
        String compensationNotice = buildPendingCompensationNotice(platform, userId);
        if (compensationNotice == null || compensationNotice.isBlank()) {
            return reply;
        }
        return compensationNotice + "\n\n" + reply;
    }

    private Map<String, Object> buildProfileContext(ImPlatform platform, String senderId, String chatId) {
        Map<String, Object> profileContext = new LinkedHashMap<>();
        profileContext.put("imPlatform", platform.name().toLowerCase());
        profileContext.put("imSenderId", senderId == null ? "" : senderId);
        profileContext.put("imChatId", chatId == null ? "" : chatId);
        return profileContext;
    }

    private String sanitizeAndObserve(ImPlatform platform,
                                      String senderId,
                                      String chatId,
                                      String userId,
                                      String replySource,
                                      String rawReply) {
        ImReplySanitizer.Decision decision = ImReplySanitizer.inspect(rawReply);
        if (decision.sanitized()) {
            logReplyDegradation(platform, senderId, chatId, userId, replySource, decision);
        }
        return decision.sanitizedReply();
    }

    private void logReplyDegradation(ImPlatform platform,
                                     String senderId,
                                     String chatId,
                                     String userId,
                                     String replySource,
                                     ImReplySanitizer.Decision decision) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "im.reply.degraded");
        event.put("platform", platform == null ? "unknown" : platform.name().toLowerCase());
        event.put("replySource", safe(replySource));
        event.put("provider", safe(decision.provider()));
        event.put("errorCategory", safe(decision.errorCategory()));
        event.put("fallbackKind", decision.fallbackKind());
        event.put("reasons", decision.reasons());
        event.put("rawLength", decision.originalReply() == null ? 0 : decision.originalReply().trim().length());
        event.put("sanitizedLength", decision.sanitizedReply().length());
        event.put("senderHash", hashForLog(senderId));
        event.put("chatHash", hashForLog(chatId));
        event.put("userHash", hashForLog(userId));
        try {
            LOGGER.warning(objectMapper.writeValueAsString(event));
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to serialize im.reply.degraded log: " + event, ex);
        }
    }

    private String hashForLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return "n/a";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(6, hash.length); i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "n/a" : value.trim();
    }

    private Instant resolveSessionWebhookExpiry(Long sessionWebhookExpiredTime) {
        if (sessionWebhookExpiredTime == null || sessionWebhookExpiredTime <= 0L) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(sessionWebhookExpiredTime);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isSessionWebhookNearExpiry(Instant expiresAt) {
        return expiresAt != null && expiresAt.isBefore(Instant.now().plusSeconds(dingtalkAsyncReplyExpirySkewSeconds));
    }

    private boolean isWebhookUsable(Instant expiresAt) {
        return !isSessionWebhookNearExpiry(expiresAt);
    }

    private String formatAsyncResult(String reply) {
        String normalizedReply = reply == null ? "" : reply.trim();
        if (normalizedReply.isBlank()) {
            normalizedReply = "已处理完成，但当前没有可返回的文本结果。";
        }
        return dingtalkAsyncResultPrefix + "\n" + normalizedReply;
    }

    private boolean tryPushViaDingtalkOpenApi(DingtalkAsyncTaskContext context, String reply) {
        return tryPushViaDingtalkOpenApi(context.userId(), context.taskId(), context.senderId(), context.chatId(), reply);
    }

    boolean tryPushViaDingtalkOpenApi(String userId, String taskId, String senderId, String chatId, String reply) {
        if (!dingtalkOpenApiMessageClient.sendText(senderId, chatId, formatAsyncResult(reply))) {
            return false;
        }
        memoryFacade.updateLongTaskProgress(
                userId,
                taskId,
                "im-dingtalk-openapi",
                "回推钉钉结果",
                "已通过钉钉 OpenAPI 主动补发结果",
                "",
                Instant.now(),
                true
        );
        return true;
    }

    private String tryHandleDingtalkAsyncTaskIntent(ImPlatform platform, String userId, String message) {
        if (platform != ImPlatform.DINGTALK || message == null || message.isBlank()) {
            return null;
        }
        boolean resultIntent = isDingtalkResultQuery(message);
        boolean progressIntent = isDingtalkProgressQuery(message);
        if (!resultIntent && !progressIntent) {
            return null;
        }
        LongTask task = resolveQueriedDingtalkTask(userId, message);
        if (task == null) {
            return "当前没有可查询的钉钉异步任务。你也可以把任务ID发给我，我帮你精确查询。";
        }
        return resultIntent ? buildDingtalkResultReply(task, true) : buildDingtalkProgressReply(task);
    }

    private String buildDingtalkProgressReply(LongTask task) {
        StringBuilder builder = new StringBuilder("当前任务进度：");
        builder.append("\n- 任务ID：").append(task.taskId());
        builder.append("\n- 状态：").append(describeTaskStatus(task.status()));
        builder.append("\n- 进度：").append(task.progressPercent()).append("%");
        builder.append("\n- 已完成步骤：").append(task.completedSteps().size());
        builder.append("\n- 待完成步骤：").append(task.pendingSteps().size());
        if (!task.pendingSteps().isEmpty()) {
            builder.append("\n- 当前待处理：").append(task.pendingSteps().get(0));
        }
        if (task.status() == LongTaskStatus.BLOCKED && task.blockedReason() != null && !task.blockedReason().isBlank()) {
            builder.append("\n- 阻塞原因：").append(task.blockedReason());
        }
        String result = extractAsyncResult(task);
        if (task.status() == LongTaskStatus.BLOCKED && result != null) {
            builder.append("\n- 已生成结果，因主动回推未成功，我现在补发给你：\n").append(result);
            markCompensationDelivered(task, "progress-query");
        }
        return builder.toString();
    }

    private String buildDingtalkResultReply(LongTask task, boolean markCompensated) {
        String result = extractAsyncResult(task);
        if (result == null || result.isBlank()) {
            return "这个任务暂时还没有可查看的最终结果。当前状态是："
                    + describeTaskStatus(task.status())
                    + "。如果你愿意，可以稍后回复“查进度”继续确认。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("任务结果（").append(task.taskId()).append("）：");
        if (task.status() == LongTaskStatus.BLOCKED) {
            builder.append("\n之前 sessionWebhook 已失效或回推失败，我现在补发给你：");
        }
        builder.append("\n").append(result);
        if (markCompensated && task.status() == LongTaskStatus.BLOCKED) {
            markCompensationDelivered(task, "result-query");
        }
        return builder.toString();
    }

    private String buildPendingCompensationNotice(ImPlatform platform, String userId) {
        if (platform != ImPlatform.DINGTALK) {
            return null;
        }
        List<LongTask> pending = memoryFacade.listLongTasks(userId, LongTaskStatus.BLOCKED.name()).stream()
                .filter(this::isPendingCompensationTask)
                .limit(MAX_COMPENSATION_RESULTS)
                .toList();
        if (pending.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("补偿通知：我发现你有之前未成功送达的处理结果，先补发给你：");
        for (LongTask task : pending) {
            String result = extractAsyncResult(task);
            builder.append("\n\n[任务ID ").append(task.taskId()).append("]");
            if (task.blockedReason() != null && !task.blockedReason().isBlank()) {
                builder.append("\n原因：").append(task.blockedReason());
            }
            if (result != null && !result.isBlank()) {
                builder.append("\n结果：").append(result);
            }
            markCompensationDelivered(task, "next-message");
        }
        return builder.toString();
    }

    private boolean isPendingCompensationTask(LongTask task) {
        return task != null
                && DINGTALK_ASYNC_TASK_TITLE.equals(task.title())
                && task.status() == LongTaskStatus.BLOCKED
                && extractAsyncResult(task) != null
                && !hasTaskNotePrefix(task, DINGTALK_COMPENSATED_NOTE_PREFIX);
    }

    private void markCompensationDelivered(LongTask task, String channel) {
        if (!isPendingCompensationTask(task)) {
            return;
        }
        String completedStep = task.pendingSteps().contains("回推钉钉结果")
                ? "回推钉钉结果"
                : null;
        memoryFacade.updateLongTaskProgress(
                task.userId(),
                task.taskId(),
                "im-dingtalk-compensation",
                completedStep,
                DINGTALK_COMPENSATED_NOTE_PREFIX + channel,
                "",
                Instant.now(),
                true
        );
    }

    private LongTask resolveQueriedDingtalkTask(String userId, String message) {
        String explicitTaskId = extractTaskId(message);
        if (explicitTaskId != null) {
            LongTask task = memoryFacade.getLongTask(userId, explicitTaskId);
            if (task != null && DINGTALK_ASYNC_TASK_TITLE.equals(task.title())) {
                return task;
            }
        }
        return memoryFacade.listLongTasks(userId, null).stream()
                .filter(task -> DINGTALK_ASYNC_TASK_TITLE.equals(task.title()))
                .findFirst()
                .orElse(null);
    }

    private String extractTaskId(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = TASK_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean isDingtalkProgressQuery(String message) {
        String normalized = memoryConsolidationService.normalizeText(message).toLowerCase();
        return containsAny(normalized, DINGTALK_PROGRESS_QUERY_TERMS);
    }

    private boolean isDingtalkResultQuery(String message) {
        String normalized = memoryConsolidationService.normalizeText(message).toLowerCase();
        return containsAny(normalized, DINGTALK_RESULT_QUERY_TERMS);
    }

    private String describeTaskStatus(LongTaskStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "等待处理";
            case RUNNING -> "处理中";
            case BLOCKED -> "未成功送达";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    private String extractAsyncResult(LongTask task) {
        if (task == null || task.recentNotes() == null) {
            return null;
        }
        for (int i = task.recentNotes().size() - 1; i >= 0; i--) {
            String normalized = stripNoteAuthor(task.recentNotes().get(i));
            if (normalized.startsWith(DINGTALK_RESULT_NOTE_PREFIX)) {
                return normalized.substring(DINGTALK_RESULT_NOTE_PREFIX.length()).trim();
            }
        }
        return null;
    }

    private boolean hasTaskNotePrefix(LongTask task, String prefix) {
        if (task == null || task.recentNotes() == null || prefix == null) {
            return false;
        }
        for (String note : task.recentNotes()) {
            if (stripNoteAuthor(note).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String stripNoteAuthor(String note) {
        if (note == null) {
            return "";
        }
        int separator = note.indexOf(": ");
        if (separator < 0 || separator + 2 >= note.length()) {
            return note;
        }
        return note.substring(separator + 2);
    }

    private String tryHandleMemoryPlanningIntent(String userId, String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.trim();
        if (MemoryIntentNlu.isAffirmativeIntent(normalized)) {
            String pendingSource = pendingKeyPointReviews.remove(userId);
            if (pendingSource != null && !pendingSource.isBlank()) {
                return buildKeyPointReviewReply(userId, pendingSource);
            }
        }
        if (isTodoGenerationIntent(normalized)) {
            List<String> pendingPoints = pendingTodoFromReview.remove(userId);
            if (pendingPoints != null && !pendingPoints.isEmpty()) {
                return buildTodoChecklistReply(pendingPoints);
            }
        }

        String sample = MemoryIntentNlu.extractAutoTuneSample(normalized);
        if (sample != null || normalized.contains("自动微调记忆风格") || normalized.contains("微调记忆风格")) {
            MemoryStyleProfile updated = memoryFacade.updateMemoryStyleProfile(
                    userId,
                    new MemoryStyleProfile(null, null, null),
                    true,
                    sample == null ? normalized : sample
            );
            return "记忆风格已微调: " + updated.styleName()
                    + "，语气=" + updated.tone()
                    + "，格式=" + updated.outputFormat();
        }
        MemoryIntentNlu.StyleUpdateIntent styleUpdateIntent = MemoryIntentNlu.extractStyleUpdateIntent(normalized);
        if (styleUpdateIntent != null && styleUpdateIntent.hasValues()) {
            MemoryStyleProfile updated = memoryFacade.updateMemoryStyleProfile(userId,
                    new MemoryStyleProfile(styleUpdateIntent.styleName(), styleUpdateIntent.tone(), styleUpdateIntent.outputFormat()));
            return "记忆风格已更新: " + updated.styleName()
                    + "，语气=" + updated.tone()
                    + "，格式=" + updated.outputFormat();
        }
        if (MemoryIntentNlu.isStyleShowIntent(normalized)) {
            MemoryStyleProfile style = memoryFacade.getMemoryStyleProfile(userId);
            return "当前记忆风格: " + style.styleName()
                    + "，语气=" + style.tone()
                    + "，格式=" + style.outputFormat();
        }

        MemoryIntentNlu.CompressionIntent compressionIntent = MemoryIntentNlu.extractCompressionIntent(normalized);
        if (compressionIntent == null) {
            return null;
        }

        String source = compressionIntent.source() == null || compressionIntent.source().isBlank()
                ? normalized
                : compressionIntent.source();
        String focus = compressionIntent.focus();

        MemoryCompressionPlan plan = memoryFacade.buildMemoryCompressionPlan(
                userId,
                source,
                new MemoryStyleProfile(null, null, null),
                focus
        );
        String styled = plan.steps().stream()
                .filter(step -> "STYLED".equals(step.stage()))
                .map(MemoryCompressionStep::content)
                .findFirst()
                .orElse("已生成记忆压缩规划。");
        return styled + "\n" + summarizeCompression(userId, source, styled);
    }

    private String summarizeCompression(String userId, String rawText, String styledText) {
        String raw = memoryConsolidationService.normalizeText(rawText);
        String styled = memoryConsolidationService.normalizeText(styledText);
        int rawLength = raw.length();
        int styledLength = styled.length();
        double ratio = rawLength == 0 ? 0.0 : (double) styledLength / rawLength;
        String ratioText = String.format("压缩后约为原文的 %.1f%%", ratio * 100.0);

        boolean keySignalIn = memoryConsolidationService.containsKeySignal(raw);
        boolean keySignalOut = memoryConsolidationService.containsKeySignal(styled);
        String keySignalHint;
        if (!keySignalIn) {
            pendingKeyPointReviews.remove(userId);
            pendingTodoFromReview.remove(userId);
            keySignalHint = "未发现明显的硬性约束。";
        } else if (keySignalOut) {
            pendingKeyPointReviews.remove(userId);
            pendingTodoFromReview.remove(userId);
            keySignalHint = "关键约束已保留。";
        } else {
            pendingKeyPointReviews.put(userId, raw);
            pendingTodoFromReview.remove(userId);
            keySignalHint = "我识别到关键约束，但压缩后可能有少量信息被弱化。"
                    + "如果你愿意，我可以再列出原文关键点给你逐条复核。";
        }
        return "我已帮你完成记忆整理，" + ratioText + "。" + keySignalHint;
    }

    private String buildKeyPointReviewReply(String userId, String sourceText) {
        List<String> points = extractReviewPoints(sourceText);
        if (points.isEmpty()) {
            return "我这边暂时没提炼出明确关键点。你可以直接发原文，我再按条帮你复核。";
        }
        StringBuilder builder = new StringBuilder("好的，我为你整理了原文关键点，请快速复核：");
        for (int i = 0; i < points.size(); i++) {
            builder.append("\n").append(i + 1).append(") ").append(points.get(i));
        }
        pendingTodoFromReview.put(userId, List.copyOf(points));
        builder.append("\n如果你愿意，回复“生成待办”，我可以把这些关键点转成执行清单。");
        return builder.toString();
    }

    private String buildTodoChecklistReply(List<String> points) {
        TodoPriorityPolicy policy = resolveTodoPriorityPolicy();
        List<String> sorted = sortByPriority(points);
        List<String> today = new ArrayList<>();
        List<String> thisWeek = new ArrayList<>();
        List<String> later = new ArrayList<>();
        for (String point : sorted) {
            switch (classifyBucket(point)) {
                case "today" -> today.add(point);
                case "this-week" -> thisWeek.add(point);
                default -> later.add(point);
            }
        }

        StringBuilder builder = new StringBuilder("好的，已根据关键点整理执行清单：");
        appendPriorityLegend(builder, policy);
        appendPolicyPreview(builder, policy);
        appendBucket(builder, "今天（today）", today, policy);
        appendBucket(builder, "本周（this week）", thisWeek, policy);
        appendBucket(builder, "后续（later）", later, policy);
        if (today.isEmpty() && thisWeek.isEmpty() && later.isEmpty()) {
            builder.append("\n1) 暂无可执行条目，请补充更具体的行动描述。");
        }
        return builder.toString();
    }

    private void appendPriorityLegend(StringBuilder builder, TodoPriorityPolicy policy) {
        builder.append("\n").append(policy.legend()).append("\n");
    }

    private void appendPolicyPreview(StringBuilder builder, TodoPriorityPolicy policy) {
        builder.append("当前待办策略：P1>= ")
                .append(policy.p1Threshold())
                .append("，P2>= ")
                .append(policy.p2Threshold())
                .append("；P1=")
                .append(policy.p1Window())
                .append("，P2=")
                .append(policy.p2Window())
                .append("，P3=")
                .append(policy.p3Window())
                .append("。\n");
    }

    private void appendBucket(StringBuilder builder, String title, List<String> items, TodoPriorityPolicy policy) {
        if (items.isEmpty()) {
            return;
        }
        builder.append("\n[").append(title).append("]");
        for (int i = 0; i < items.size(); i++) {
            String formatted = formatTodoItem(items.get(i), policy);
            builder.append("\n").append(i + 1).append(") ").append(formatted);
        }
    }

    private String formatTodoItem(String point, TodoPriorityPolicy policy) {
        int score = priorityScore(point);
        String priority = score >= policy.p1Threshold() ? "P1" : (score >= policy.p2Threshold() ? "P2" : "P3");
        String action = actionVerb(point);
        String cleaned = normalizeActionText(point);
        return priority + " " + action + "：" + cleaned + "（" + suggestedWindow(priority, policy) + "）";
    }

    private String suggestedWindow(String priority, TodoPriorityPolicy policy) {
        return switch (priority) {
            case "P1" -> policy.p1Window();
            case "P2" -> policy.p2Window();
            default -> policy.p3Window();
        };
    }

    private TodoPriorityPolicy resolveTodoPriorityPolicy() {
        int p1Threshold = readPositiveIntProperty(PROP_TODO_P1_THRESHOLD, 45);
        int p2Threshold = readPositiveIntProperty(PROP_TODO_P2_THRESHOLD, 25);
        if (p2Threshold > p1Threshold) {
            p2Threshold = p1Threshold;
        }
        String p1Window = readTextProperty(PROP_TODO_WINDOW_P1, "建议24小时内完成");
        String p2Window = readTextProperty(PROP_TODO_WINDOW_P2, "建议3天内完成");
        String p3Window = readTextProperty(PROP_TODO_WINDOW_P3, "建议本周内完成");
        String legend = readTextProperty(PROP_TODO_LEGEND, "优先级说明：P1=今天必须完成，P2=3天内推进，P3=本周内安排。");
        return new TodoPriorityPolicy(p1Threshold, p2Threshold, p1Window, p2Window, p3Window, legend);
    }

    private int readPositiveIntProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String readTextProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String actionVerb(String point) {
        String normalized = memoryConsolidationService.normalizeText(point).toLowerCase();
        if (containsAny(normalized, "提交", "send", "commit")) {
            return "提交";
        }
        if (containsAny(normalized, "检查", "核对", "确认", "review", "check")) {
            return "核对";
        }
        if (containsAny(normalized, "联系", "沟通", "通知", "call", "contact")) {
            return "联系";
        }
        if (containsAny(normalized, "安排", "计划", "prepare", "plan")) {
            return "安排";
        }
        return "执行";
    }

    private String normalizeActionText(String point) {
        String text = memoryConsolidationService.normalizeText(point);
        return text
                .replaceFirst("^(请|需要|要|必须|务必|尽快)\\s*", "")
                .trim();
    }

    private List<String> sortByPriority(List<String> points) {
        return points.stream()
                .sorted((left, right) -> Integer.compare(priorityScore(right), priorityScore(left)))
                .toList();
    }

    private int priorityScore(String point) {
        String normalized = memoryConsolidationService.normalizeText(point).toLowerCase();
        int score = 0;
        if (containsAny(normalized, "今天", "今日", "今晚", "today", "立即", "马上")) {
            score += 30;
        }
        if (containsAny(normalized, "明天", "后天", "本周", "这周", "周", "this week", "tomorrow")) {
            score += 20;
        }
        if (normalized.matches(".*\\d{1,2}[:：]\\d{2}.*") || containsAny(normalized, "截止", "deadline", "due")) {
            score += 15;
        }
        if (memoryConsolidationService.containsKeySignal(normalized)) {
            score += 10;
        }
        return score;
    }

    private String classifyBucket(String point) {
        String normalized = memoryConsolidationService.normalizeText(point).toLowerCase();
        if (containsAny(normalized, "今天", "今日", "今晚", "today", "立即", "马上")
                || normalized.matches(".*\\d{1,2}[:：]\\d{2}.*")) {
            return "today";
        }
        if (containsAny(normalized, "明天", "后天", "本周", "这周", "周", "this week", "tomorrow")) {
            return "this-week";
        }
        return "later";
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTodoGenerationIntent(String message) {
        String normalized = memoryConsolidationService.normalizeText(message).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("生成待办")
                || normalized.contains("转成待办")
                || normalized.contains("行动清单")
                || normalized.contains("todo list")
                || "待办".equals(normalized)
                || "todo".equals(normalized);
    }

    private List<String> extractReviewPoints(String sourceText) {
        String normalized = memoryConsolidationService.normalizeText(sourceText);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] parts = normalized.split("[\\n。！？!?；;]+");
        LinkedHashSet<String> prioritized = new LinkedHashSet<>();
        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        for (String part : parts) {
            String line = memoryConsolidationService.normalizeText(part);
            if (line.isBlank()) {
                continue;
            }
            if (memoryConsolidationService.containsKeySignal(line)) {
                prioritized.add(line);
            } else {
                fallback.add(line);
            }
        }
        List<String> selected = new java.util.ArrayList<>();
        for (String line : prioritized) {
            if (selected.size() >= 5) {
                break;
            }
            selected.add(line);
        }
        for (String line : fallback) {
            if (selected.size() >= 5) {
                break;
            }
            selected.add(line);
        }
        return selected;
    }


    private String buildUserId(ImPlatform platform, String senderId) {
        String normalizedSender = senderId == null || senderId.isBlank() ? "anonymous" : senderId.trim();
        return "im:" + platform.name().toLowerCase() + ":" + normalizedSender;
    }

    private record TodoPriorityPolicy(
            int p1Threshold,
            int p2Threshold,
            String p1Window,
            String p2Window,
            String p3Window,
            String legend
    ) {
    }

    record AsyncReplyAck(String taskId, String acceptedReply) {
    }

    private record DingtalkAsyncTaskContext(
            String taskId,
            String userId,
            String senderId,
            String chatId,
            String inputText,
            String sessionWebhook,
            Instant sessionWebhookExpiresAt
    ) {
    }
}

package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dingtalk.open.app.api.chatbot.BotReplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class DingtalkOpenApiConversationSender implements DingtalkConversationSender {

    private static final Logger LOGGER = Logger.getLogger(DingtalkOpenApiConversationSender.class.getName());
    private static final URI SEND_MESSAGE_URI = URI.create("https://api.dingtalk.com/v1.0/im/chat/messages/send");
    private static final String TOKEN_ENDPOINT = "https://oapi.dingtalk.com/gettoken";
    private static final String DEFAULT_UPDATE_MESSAGE_URL = "https://api.dingtalk.com/v1.0/im/chat/messages/update";

    private final DingtalkIntegrationSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiresAt = Instant.EPOCH;
    private volatile long lastTokenMonitorLogAtMillis = 0L;
    private volatile Instant lastTokenRefreshAt = Instant.EPOCH;
    private volatile String lastTokenFailureReason = "";
    private final Object accessTokenLock = new Object();

    @org.springframework.beans.factory.annotation.Value("${mindos.im.dingtalk.outbound.update-url:https://api.dingtalk.com/v1.0/im/chat/messages/update}")
    private String updateMessageUrl = DEFAULT_UPDATE_MESSAGE_URL;

    @Autowired
    public DingtalkOpenApiConversationSender(DingtalkIntegrationSettings settings) {
        this(settings,
                new ObjectMapper().findAndRegisterModules(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    DingtalkOpenApiConversationSender(DingtalkIntegrationSettings settings,
                                      ObjectMapper objectMapper,
                                      HttpClient httpClient) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isReady() {
        return settings.outboundMessagingEnabled() && settings.hasOutboundCredentials();
    }

    @Override
    public boolean sendText(String openConversationId, String text) {
        return sendText(openConversationId, text, "");
    }

    @Override
    public boolean sendText(String openConversationId, String text, String sessionWebhook) {
        return sendMessage(openConversationId, "text", Map.of("content", safe(text)), sessionWebhook, safe(text).length()).sent();
    }

    @Override
    public boolean sendMarkdownCard(String openConversationId,
                                    String title,
                                    String markdown,
                                    String sessionWebhook) {
        return sendMarkdownCardHandle(openConversationId, title, markdown, sessionWebhook).sent();
    }

    @Override
    public DingtalkMessageHandle sendMarkdownCardHandle(String openConversationId,
                                                        String title,
                                                        String markdown,
                                                        String sessionWebhook) {
        String normalizedTitle = safe(title).isBlank() ? "MindOS" : safe(title);
        String normalizedMarkdown = safe(markdown);
        String webhook = safe(sessionWebhook);
        String conversationId = safe(openConversationId);
        if (!webhook.isBlank()) {
            try {
                String response = BotReplier.fromWebhook(webhook).replyMarkdown(normalizedTitle, normalizedMarkdown);
                logEvent(Level.INFO, "dingtalk.stream.outbound.sent", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", normalizedMarkdown.length(),
                        "channel", "session_webhook",
                        "msgType", "markdown"
                ));
                String platformMessageId = extractPlatformMessageId(response);
                return platformMessageId.isBlank()
                        ? DingtalkMessageHandle.sentWithoutUpdate(conversationId, webhook)
                        : DingtalkMessageHandle.updatable(conversationId, webhook, platformMessageId);
            } catch (Exception ex) {
                logEvent(Level.WARNING, "dingtalk.stream.outbound.webhook-failed", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", normalizedMarkdown.length(),
                        "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : clip(ex.getMessage())
                ));
            }
        }
        Map<String, Object> markdownPayload = new LinkedHashMap<>();
        markdownPayload.put("title", normalizedTitle);
        markdownPayload.put("text", normalizedMarkdown);
        return sendMessage(openConversationId, "markdown", markdownPayload, sessionWebhook, normalizedMarkdown.length());
    }

    @Override
    public boolean updateMessage(String openConversationId, String updateText, String sessionWebhook) {
        return sendText(openConversationId, updateText, sessionWebhook);
    }

    @Override
    public boolean updateMessage(DingtalkMessageHandle handle,
                                 String title,
                                 String markdown,
                                 String sessionWebhook) {
        if (handle == null || !handle.sent()) {
            return false;
        }
        if (!handle.canUpdate()) {
            return false;
        }
        String accessToken;
        try {
            accessToken = resolveAccessToken();
        } catch (Exception ex) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.update.failed", Map.of(
                    "conversationHash", safeHash(handle.conversationId()),
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : clip(ex.getMessage())
            ), ex);
            return false;
        }
        if (!isAllowedUpdateEndpoint(updateMessageUrl)) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgType", "markdown");
            payload.put("messageId", handle.platformMessageId());
            payload.put("robotCode", settings.effectiveRobotCode());
            payload.put("openConversationId", handle.conversationId());
            payload.put("msgContent", objectMapper.writeValueAsString(Map.of(
                    "title", safe(title).isBlank() ? "MindOS" : safe(title),
                    "text", safe(markdown)
            )));
            HttpRequest request = HttpRequest.newBuilder(URI.create(updateMessageUrl.trim()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                logEvent(Level.WARNING, "dingtalk.stream.outbound.update.failed", Map.of(
                        "conversationHash", safeHash(handle.conversationId()),
                        "status", response.statusCode(),
                        "body", clip(response.body())
                ));
                return false;
            }
            logEvent(Level.INFO, "dingtalk.stream.outbound.updated", Map.of(
                    "conversationHash", safeHash(handle.conversationId()),
                    "msgType", "markdown"
            ));
            return true;
        } catch (Exception ex) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.update.failed", Map.of(
                    "conversationHash", safeHash(handle.conversationId()),
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : clip(ex.getMessage())
            ), ex);
            return false;
        }
    }

    private DingtalkMessageHandle sendMessage(String openConversationId,
                                              String msgType,
                                              Map<String, Object> msgContent,
                                              String sessionWebhook,
                                              int replyLength) {
        String conversationId = safe(openConversationId);
        if (!isReady() || conversationId.isBlank() || msgContent == null || msgContent.isEmpty()) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.skipped", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "reason", !isReady() ? "sender_not_ready" : "blank_conversation_or_message",
                    "replyLength", Math.max(0, replyLength)
            ));
            return DingtalkMessageHandle.notSent(conversationId, sessionWebhook);
        }
        if ("text".equals(msgType) && trySendBySessionWebhook(conversationId, safe(String.valueOf(msgContent.get("content"))), sessionWebhook)) {
            return DingtalkMessageHandle.sentWithoutUpdate(conversationId, sessionWebhook);
        }
        try {
            String accessToken = resolveAccessToken();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("openConversationIds", List.of(conversationId));
            payload.put("robotCode", settings.effectiveRobotCode());
            payload.put("msgType", msgType);
            payload.put("msgContent", objectMapper.writeValueAsString(msgContent));
            String requestBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(SEND_MESSAGE_URI)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                logEvent(Level.WARNING, "dingtalk.stream.outbound.failed", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", Math.max(0, replyLength),
                        "status", response.statusCode(),
                        "body", clip(response.body())
                ));
                return DingtalkMessageHandle.notSent(conversationId, sessionWebhook);
            }
            JsonNode body = parseJson(response.body());
            boolean success = body.path("success").asBoolean(true);
            if (!success) {
                logEvent(Level.WARNING, "dingtalk.stream.outbound.rejected", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", Math.max(0, replyLength),
                        "body", clip(response.body())
                ));
            } else {
                logEvent(Level.INFO, "dingtalk.stream.outbound.sent", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", Math.max(0, replyLength),
                        "msgType", msgType
                ));
            }
            if (!success) {
                return DingtalkMessageHandle.notSent(conversationId, sessionWebhook);
            }
            String platformMessageId = extractPlatformMessageId(body);
            return platformMessageId.isBlank()
                    ? DingtalkMessageHandle.sentWithoutUpdate(conversationId, sessionWebhook)
                    : DingtalkMessageHandle.updatable(conversationId, sessionWebhook, platformMessageId);
        } catch (Exception ex) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.exception", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "replyLength", Math.max(0, replyLength),
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : clip(ex.getMessage())
            ), ex);
            return DingtalkMessageHandle.notSent(conversationId, sessionWebhook);
        }
    }

    private String resolveAccessToken() throws Exception {
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(cachedAccessTokenExpiresAt)) {
            maybeLogTokenMonitor("cache_hit", now);
            return cachedAccessToken;
        }
        synchronized (accessTokenLock) {
            now = Instant.now();
            if (cachedAccessToken != null && now.isBefore(cachedAccessTokenExpiresAt)) {
                maybeLogTokenMonitor("cache_hit", now);
                return cachedAccessToken;
            }
            String url = TOKEN_ENDPOINT
                    + "?appkey=" + encode(settings.effectiveOutboundAppKey())
                    + "&appsecret=" + encode(settings.effectiveOutboundAppSecret());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                lastTokenFailureReason = "http_status:" + response.statusCode();
                logEvent(Level.WARNING, "dingtalk.stream.token.failed", Map.of(
                        "status", response.statusCode(),
                        "reason", "http_status",
                        "body", clip(response.body())
                ));
                throw new IllegalStateException("token http status=" + response.statusCode());
            }
            JsonNode body = parseJson(response.body());
            if (body.path("errcode").asInt(0) != 0) {
                lastTokenFailureReason = "errcode:" + body.path("errcode").asInt();
                logEvent(Level.WARNING, "dingtalk.stream.token.failed", Map.of(
                        "reason", "errcode",
                        "errcode", body.path("errcode").asInt(),
                        "errmsg", body.path("errmsg").asText("unknown")
                ));
                throw new IllegalStateException("token errcode=" + body.path("errcode").asInt()
                        + ", errmsg=" + body.path("errmsg").asText("unknown"));
            }
            String accessToken = body.path("access_token").asText("").trim();
            if (accessToken.isBlank()) {
                lastTokenFailureReason = "empty_access_token";
                logEvent(Level.WARNING, "dingtalk.stream.token.failed", Map.of(
                        "reason", "empty_access_token"
                ));
                throw new IllegalStateException("empty access_token");
            }
            long expiresInSeconds = Math.max(300L, body.path("expires_in").asLong(7200L));
            cachedAccessToken = accessToken;
            cachedAccessTokenExpiresAt = Instant.now().plusSeconds(Math.max(60L, expiresInSeconds - 120L));
            lastTokenRefreshAt = Instant.now();
            lastTokenFailureReason = "";
            logEvent(Level.INFO, "dingtalk.stream.token.refreshed", Map.of(
                    "expiresInSeconds", expiresInSeconds
            ));
            maybeLogTokenMonitor("refreshed", Instant.now());
            return accessToken;
        }
    }

    private void maybeLogTokenMonitor(String source, Instant now) {
        long nowMillis = now == null ? System.currentTimeMillis() : now.toEpochMilli();
        if (nowMillis - lastTokenMonitorLogAtMillis < 60_000L) {
            return;
        }
        long remainingSeconds = Math.max(0L, Duration.between(now == null ? Instant.now() : now, cachedAccessTokenExpiresAt).toSeconds());
        lastTokenMonitorLogAtMillis = nowMillis;
        logEvent(Level.INFO, "dingtalk.stream.token.monitor", Map.of(
                "source", source,
                "remainingSeconds", remainingSeconds,
                "hasCachedToken", cachedAccessToken != null && !cachedAccessToken.isBlank()
        ));
    }

    Map<String, Object> tokenMonitorSnapshot() {
        Instant now = Instant.now();
        long remainingSeconds = Math.max(0L, Duration.between(now, cachedAccessTokenExpiresAt).toSeconds());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("senderReady", isReady());
        snapshot.put("hasCachedToken", cachedAccessToken != null && !cachedAccessToken.isBlank());
        snapshot.put("remainingSeconds", remainingSeconds);
        snapshot.put("expiresAt", cachedAccessTokenExpiresAt.equals(Instant.EPOCH) ? "" : cachedAccessTokenExpiresAt.toString());
        snapshot.put("lastRefreshAt", lastTokenRefreshAt.equals(Instant.EPOCH) ? "" : lastTokenRefreshAt.toString());
        snapshot.put("lastFailureReason", safe(lastTokenFailureReason));
        snapshot.put("updateUrl", safe(updateMessageUrl));
        return Map.copyOf(snapshot);
    }

    private String extractPlatformMessageId(String responseBody) {
        try {
            return extractPlatformMessageId(parseJson(responseBody));
        } catch (Exception ex) {
            return "";
        }
    }

    private String extractPlatformMessageId(JsonNode body) {
        if (body == null || body.isMissingNode()) {
            return "";
        }
        for (String field : List.of("messageId", "msgId", "processQueryKey", "outTrackId")) {
            String value = body.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        JsonNode data = body.path("data");
        if (!data.isMissingNode()) {
            for (String field : List.of("messageId", "msgId", "processQueryKey", "outTrackId")) {
                String value = data.path(field).asText("").trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private boolean isAllowedUpdateEndpoint(String url) {
        try {
            URI uri = URI.create(safe(url));
            String scheme = safe(uri.getScheme()).toLowerCase();
            String host = safe(uri.getHost()).toLowerCase();
            return !host.isBlank() && "https".equals(scheme);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean trySendBySessionWebhook(String conversationId, String message, String sessionWebhook) {
        String webhook = safe(sessionWebhook);
        if (webhook.isBlank()) {
            return false;
        }
        try {
            BotReplier.fromWebhook(webhook).replyText(message);
            logEvent(Level.INFO, "dingtalk.stream.outbound.sent", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "replyLength", message.length(),
                    "channel", "session_webhook"
            ));
            return true;
        } catch (Exception ex) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.webhook-failed", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "replyLength", message.length(),
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : clip(ex.getMessage())
            ));
            return false;
        }
    }

    private JsonNode parseJson(String rawBody) throws Exception {
        return objectMapper.readTree(rawBody == null ? "{}" : rawBody);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeHash(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return Integer.toHexString(value.trim().hashCode());
    }

    private String clip(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
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


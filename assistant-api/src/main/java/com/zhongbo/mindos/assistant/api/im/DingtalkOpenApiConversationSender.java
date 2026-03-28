package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final DingtalkIntegrationSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiresAt = Instant.EPOCH;
    private final Object accessTokenLock = new Object();

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
        String conversationId = safe(openConversationId);
        String message = safe(text);
        if (!isReady() || conversationId.isBlank() || message.isBlank()) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.skipped", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "reason", !isReady() ? "sender_not_ready" : "blank_conversation_or_message",
                    "replyLength", message.length()
            ));
            return false;
        }
        try {
            String accessToken = resolveAccessToken();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("openConversationIds", List.of(conversationId));
            payload.put("robotCode", settings.effectiveRobotCode());
            payload.put("msgType", "text");
            payload.put("msgContent", objectMapper.writeValueAsString(Map.of("content", message)));
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
                        "replyLength", message.length(),
                        "status", response.statusCode(),
                        "body", clip(response.body())
                ));
                return false;
            }
            JsonNode body = parseJson(response.body());
            boolean success = body.path("success").asBoolean(true);
            if (!success) {
                logEvent(Level.WARNING, "dingtalk.stream.outbound.rejected", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", message.length(),
                        "body", clip(response.body())
                ));
            } else {
                logEvent(Level.INFO, "dingtalk.stream.outbound.sent", Map.of(
                        "conversationHash", safeHash(conversationId),
                        "replyLength", message.length()
                ));
            }
            return success;
        } catch (Exception ex) {
            logEvent(Level.WARNING, "dingtalk.stream.outbound.exception", Map.of(
                    "conversationHash", safeHash(conversationId),
                    "replyLength", message.length(),
                    "reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : clip(ex.getMessage())
            ), ex);
            return false;
        }
    }

    private String resolveAccessToken() throws Exception {
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(cachedAccessTokenExpiresAt)) {
            return cachedAccessToken;
        }
        synchronized (accessTokenLock) {
            now = Instant.now();
            if (cachedAccessToken != null && now.isBefore(cachedAccessTokenExpiresAt)) {
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
                logEvent(Level.WARNING, "dingtalk.stream.token.failed", Map.of(
                        "status", response.statusCode(),
                        "reason", "http_status",
                        "body", clip(response.body())
                ));
                throw new IllegalStateException("token http status=" + response.statusCode());
            }
            JsonNode body = parseJson(response.body());
            if (body.path("errcode").asInt(0) != 0) {
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
                logEvent(Level.WARNING, "dingtalk.stream.token.failed", Map.of(
                        "reason", "empty_access_token"
                ));
                throw new IllegalStateException("empty access_token");
            }
            long expiresInSeconds = Math.max(300L, body.path("expires_in").asLong(7200L));
            cachedAccessToken = accessToken;
            cachedAccessTokenExpiresAt = Instant.now().plusSeconds(Math.max(60L, expiresInSeconds - 120L));
            logEvent(Level.INFO, "dingtalk.stream.token.refreshed", Map.of(
                    "expiresInSeconds", expiresInSeconds
            ));
            return accessToken;
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


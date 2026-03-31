package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class DingtalkOpenApiMessageClient {

    private static final Logger LOGGER = Logger.getLogger(DingtalkOpenApiMessageClient.class.getName());

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final boolean enabled;
    private final String appKey;
    private final String appSecret;
    private final String robotCode;
    private final String accessTokenUrl;
    private final String sendToConversationUrl;
    private final String batchSendUrl;
    private final SendMode preferredSendMode;
    private final long refreshSkewSeconds;
    private final List<String> allowedHosts;
    private final boolean allowInsecureLocalhostHttp;

    private volatile CachedAccessToken cachedAccessToken;

    @Autowired
    public DingtalkOpenApiMessageClient(
            @Value("${mindos.im.dingtalk.openapi-fallback.enabled:false}") boolean enabled,
            @Value("${mindos.im.dingtalk.openapi-fallback.app-key:}") String appKey,
            @Value("${mindos.im.dingtalk.openapi-fallback.app-secret:}") String appSecret,
            @Value("${mindos.im.dingtalk.openapi-fallback.robot-code:}") String robotCode,
            @Value("${mindos.im.dingtalk.openapi-fallback.access-token-url:https://api.dingtalk.com/v1.0/oauth2/accessToken}") String accessTokenUrl,
            @Value("${mindos.im.dingtalk.openapi-fallback.send-to-conversation-url:https://api.dingtalk.com/v1.0/im/messages/sendToConversation}") String sendToConversationUrl,
            @Value("${mindos.im.dingtalk.openapi-fallback.batch-send-url:https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend}") String batchSendUrl,
            @Value("${mindos.im.dingtalk.openapi-fallback.preferred-send-mode:conversation-first}") String preferredSendMode,
            @Value("${mindos.im.dingtalk.openapi-fallback.access-token-refresh-skew-seconds:60}") long refreshSkewSeconds,
            @Value("${mindos.im.dingtalk.openapi-fallback.connect-timeout-ms:3000}") long connectTimeoutMillis,
            @Value("${mindos.im.dingtalk.openapi-fallback.request-timeout-ms:10000}") long requestTimeoutMillis,
            @Value("${mindos.im.dingtalk.openapi-fallback.allowed-hosts:localhost,127.0.0.1,.dingtalk.com,.dingtalkapps.com}") String allowedHosts,
            @Value("${mindos.im.dingtalk.openapi-fallback.allow-insecure-localhost-http:false}") boolean allowInsecureLocalhostHttp) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1000L, connectTimeoutMillis)))
                        .build(),
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofMillis(Math.max(1000L, requestTimeoutMillis)),
                enabled,
                appKey,
                appSecret,
                robotCode,
                accessTokenUrl,
                sendToConversationUrl,
                batchSendUrl,
                parseSendMode(preferredSendMode),
                refreshSkewSeconds,
                parseAllowedHosts(allowedHosts),
                allowInsecureLocalhostHttp);
    }

    DingtalkOpenApiMessageClient(HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 Duration requestTimeout,
                                 boolean enabled,
                                 String appKey,
                                 String appSecret,
                                 String robotCode,
                                 String accessTokenUrl,
                                 String sendToConversationUrl,
                                 String batchSendUrl,
                                 SendMode preferredSendMode,
                                 long refreshSkewSeconds,
                                 List<String> allowedHosts,
                                 boolean allowInsecureLocalhostHttp) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.enabled = enabled;
        this.appKey = normalize(appKey);
        this.appSecret = normalize(appSecret);
        this.robotCode = normalize(robotCode);
        this.accessTokenUrl = normalize(accessTokenUrl);
        this.sendToConversationUrl = normalize(sendToConversationUrl);
        this.batchSendUrl = normalize(batchSendUrl);
        this.preferredSendMode = preferredSendMode == null ? SendMode.CONVERSATION_FIRST : preferredSendMode;
        this.refreshSkewSeconds = Math.max(0L, refreshSkewSeconds);
        this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        this.allowInsecureLocalhostHttp = allowInsecureLocalhostHttp;
    }

    public boolean sendText(String senderId, String openConversationId, String text) {
        String normalizedText = normalize(text);
        if (!isEnabled() || normalizedText.isBlank()) {
            return false;
        }
        String accessToken = getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        return switch (preferredSendMode) {
            case USER_FIRST -> tryUserThenConversation(accessToken, senderId, openConversationId, normalizedText);
            case CONVERSATION_FIRST -> tryConversationThenUser(accessToken, senderId, openConversationId, normalizedText);
        };
    }

    boolean isEnabled() {
        return enabled
                && !appKey.isBlank()
                && !appSecret.isBlank()
                && !robotCode.isBlank()
                && isAllowedEndpoint(accessTokenUrl)
                && (isAllowedEndpoint(sendToConversationUrl) || isAllowedEndpoint(batchSendUrl));
    }

    private synchronized String getAccessToken() {
        CachedAccessToken cached = cachedAccessToken;
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(refreshSkewSeconds))) {
            return cached.accessToken();
        }
        if (!isAllowedEndpoint(accessTokenUrl)) {
            LOGGER.warning("DingTalk OpenAPI access token URL is not allowed: " + accessTokenUrl);
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("appKey", appKey);
            payload.put("appSecret", appSecret);
            JsonNode response = postJson(accessTokenUrl, payload, null);
            if (response == null) {
                return null;
            }
            // Accept common DingTalk token field variants across v1/legacy responses (camelCase and snake_case).
            String token = readText(response, "accessToken", "access_token");
            long expiresIn = readLong(response, 7200L, "expireIn", "expiresIn", "expires_in");
            if (token.isBlank()) {
                LOGGER.warning("DingTalk OpenAPI access token response missing token field");
                return null;
            }
            cachedAccessToken = new CachedAccessToken(token, Instant.now().plusSeconds(Math.max(300L, expiresIn)));
            return token;
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to obtain DingTalk OpenAPI access token", ex);
            return null;
        }
    }

    private boolean sendToConversation(String accessToken, String openConversationId, String text) {
        if (!isAllowedEndpoint(sendToConversationUrl)) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("robotCode", robotCode);
            payload.put("openConversationId", openConversationId.trim());
            payload.put("msgKey", "sampleText");
            payload.put("msgParam", objectMapper.writeValueAsString(Map.of("content", text)));
            JsonNode response = postJson(sendToConversationUrl, payload, accessToken);
            return isSuccessfulResponse(response);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to send DingTalk OpenAPI conversation message", ex);
            return false;
        }
    }

    private boolean sendToUsers(String accessToken, List<String> userIds, String text) {
        if (!isAllowedEndpoint(batchSendUrl) || userIds == null || userIds.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("robotCode", robotCode);
            payload.put("userIds", userIds);
            payload.put("msgKey", "sampleText");
            payload.put("msgParam", objectMapper.writeValueAsString(Map.of("content", text)));
            JsonNode response = postJson(batchSendUrl, payload, accessToken);
            return isSuccessfulResponse(response);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to send DingTalk OpenAPI user message", ex);
            return false;
        }
    }

    private JsonNode postJson(String url, Map<String, Object> payload, String accessToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken.trim());
            builder.header("x-acs-dingtalk-access-token", accessToken.trim());
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.warning("DingTalk OpenAPI request failed, status=" + response.statusCode() + ", body=" + clip(response.body()));
            return null;
        }
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }

    private boolean isSuccessfulResponse(JsonNode response) {
        if (response == null || response.isMissingNode()) {
            return false;
        }
        if (response.isEmpty()) {
            return true;
        }
        String errCode = readText(response, "errcode", "errorCode", "code");
        if (!errCode.isBlank() && !"0".equals(errCode)) {
            return false;
        }
        return response.path("success").asBoolean(true);
    }

    private boolean isAllowedEndpoint(String url) {
        String normalizedUrl = normalize(url);
        if (normalizedUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(normalizedUrl);
            String rawScheme = uri.getScheme();
            String rawHost = uri.getHost();
            String scheme = rawScheme == null ? "" : rawScheme.trim().toLowerCase(Locale.ROOT);
            String host = rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
            if (host.isBlank() || !isAllowedHost(host)) {
                return false;
            }
            if ("https".equals(scheme)) {
                return true;
            }
            return allowInsecureLocalhostHttp
                    && "http".equals(scheme)
                    && isLocalLoopbackHost(host);
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Invalid DingTalk OpenAPI endpoint", ex);
            return false;
        }
    }

    private boolean isAllowedHost(String host) {
        for (String candidate : allowedHosts) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith(".")) {
                if (host.endsWith(normalized)) {
                    return true;
                }
            } else if (host.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalLoopbackHost(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private String readText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (!text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    private long readLong(JsonNode node, long defaultValue, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                long parsed = value.asLong(defaultValue);
                if (parsed > 0L) {
                    return parsed;
                }
            }
        }
        return defaultValue;
    }

    private String clip(String text) {
        if (text == null || text.length() <= 300) {
            return text == null ? "" : text;
        }
        return text.substring(0, 300) + "...";
    }

    private static List<String> parseAllowedHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .toList();
    }

    private static SendMode parseSendMode(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "user-first", "users-first", "batch-user-first" -> SendMode.USER_FIRST;
            default -> SendMode.CONVERSATION_FIRST;
        };
    }

    private boolean tryConversationThenUser(String accessToken, String senderId, String openConversationId, String text) {
        if (!normalize(openConversationId).isBlank() && sendToConversation(accessToken, openConversationId, text)) {
            return true;
        }
        return !normalize(senderId).isBlank() && sendToUsers(accessToken, List.of(senderId.trim()), text);
    }

    private boolean tryUserThenConversation(String accessToken, String senderId, String openConversationId, String text) {
        if (!normalize(senderId).isBlank() && sendToUsers(accessToken, List.of(senderId.trim()), text)) {
            return true;
        }
        return !normalize(openConversationId).isBlank() && sendToConversation(accessToken, openConversationId, text);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    enum SendMode {
        CONVERSATION_FIRST,
        USER_FIRST
    }

    private record CachedAccessToken(String accessToken, Instant expiresAt) {
    }
}

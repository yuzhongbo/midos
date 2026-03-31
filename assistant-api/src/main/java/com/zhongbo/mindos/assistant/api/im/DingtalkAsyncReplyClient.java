package com.zhongbo.mindos.assistant.api.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class DingtalkAsyncReplyClient {

    private static final Logger LOGGER = Logger.getLogger(DingtalkAsyncReplyClient.class.getName());

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final List<String> allowedHosts;

    @Autowired
    public DingtalkAsyncReplyClient(
            @Value("${mindos.im.dingtalk.async-reply.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${mindos.im.dingtalk.async-reply.request-timeout-ms:10000}") long requestTimeoutMs,
            @Value("${mindos.im.dingtalk.async-reply.allowed-hosts:localhost,127.0.0.1,.dingtalk.com,.dingtalkapps.com}") String allowedHosts) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1000L, connectTimeoutMs)))
                        .build(),
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofMillis(Math.max(1000L, requestTimeoutMs)),
                parseAllowedHosts(allowedHosts));
    }

    DingtalkAsyncReplyClient(HttpClient httpClient,
                             ObjectMapper objectMapper,
                             Duration requestTimeout,
                             List<String> allowedHosts) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }

    public boolean isUsableSessionWebhook(String sessionWebhook) {
        if (sessionWebhook == null || sessionWebhook.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(sessionWebhook.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isBlank() || !isAllowedHost(host)) {
                return false;
            }
            if ("https".equals(scheme)) {
                return true;
            }
            return "http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host));
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Invalid DingTalk session webhook", ex);
            return false;
        }
    }

    public boolean sendText(String sessionWebhook, String text) {
        if (!isUsableSessionWebhook(sessionWebhook)) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgtype", "text");
            payload.put("text", Map.of("content", text == null ? "" : text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(sessionWebhook.trim()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return true;
            }
            LOGGER.warning("DingTalk async reply failed, status=" + statusCode + ", body=" + clip(response.body()));
            return false;
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to push async DingTalk reply", ex);
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

    private static List<String> parseAllowedHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .toList();
    }

    private String clip(String text) {
        if (text == null || text.length() <= 300) {
            return text == null ? "" : text;
        }
        return text.substring(0, 300) + "...";
    }
}

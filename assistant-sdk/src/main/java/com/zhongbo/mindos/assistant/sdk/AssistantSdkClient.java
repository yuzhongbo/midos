package com.zhongbo.mindos.assistant.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.common.dto.ChatRequestDto;
import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanResponseDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileExplainDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileDto;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skeleton SDK client for calling the MindOS server from external clients.
 */
public class AssistantSdkClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;

    public AssistantSdkClient(URI baseUri) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.baseUri = baseUri;
    }

    public ChatResponseDto chat(ChatRequestDto request) {
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            return sendForBody(httpRequest, ChatResponseDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS chat call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS chat endpoint");
        }
    }

    public MemorySyncResponseDto applyMemorySync(String userId, MemorySyncRequestDto request, int limit) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/memory/" + encodedUser + "/sync?limit=" + limit);

        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return sendForBody(httpRequest, MemorySyncResponseDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS memory sync apply call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS memory sync apply endpoint");
        }
    }

    public MemorySyncResponseDto fetchMemorySync(String userId, long since, int limit) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/memory/" + encodedUser + "/sync?since=" + since + "&limit=" + limit);

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return sendForBody(httpRequest, MemorySyncResponseDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS memory sync fetch call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS memory sync fetch endpoint");
        }
    }

    public MemoryStyleProfileDto getMemoryStyle(String userId) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/memory/" + encodedUser + "/style");

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return sendForBody(httpRequest, MemoryStyleProfileDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS memory style fetch call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS memory style fetch endpoint");
        }
    }

    public PersonaProfileDto getPersonaProfile(String userId) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/memory/" + encodedUser + "/persona");

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return sendForBody(httpRequest, PersonaProfileDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS persona profile fetch call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS persona profile endpoint");
        }
    }

    public PersonaProfileExplainDto getPersonaProfileExplain(String userId) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/memory/" + encodedUser + "/persona/explain");

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return sendForBody(httpRequest, PersonaProfileExplainDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS persona explain fetch call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS persona explain endpoint");
        }
    }

    public MemoryStyleProfileDto updateMemoryStyle(String userId, MemoryStyleProfileDto request) {
        return updateMemoryStyle(userId, request, false, null);
    }

    public MemoryStyleProfileDto updateMemoryStyle(String userId,
                                                   MemoryStyleProfileDto request,
                                                   boolean autoTune,
                                                   String sampleText) {
        String encodedUser = encodePathSegment(userId);
        StringBuilder uriBuilder = new StringBuilder("/api/memory/")
                .append(encodedUser)
                .append("/style?autoTune=")
                .append(autoTune);
        if (sampleText != null && !sampleText.isBlank()) {
            uriBuilder.append("&sampleText=")
                    .append(URLEncoder.encode(sampleText, StandardCharsets.UTF_8));
        }
        URI uri = baseUri.resolve(uriBuilder.toString());

        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return sendForBody(httpRequest, MemoryStyleProfileDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS memory style update call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS memory style update endpoint");
        }
    }

    public MemoryCompressionPlanResponseDto buildMemoryCompressionPlan(String userId,
                                                                       MemoryCompressionPlanRequestDto request) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/memory/" + encodedUser + "/compress-plan");

        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return sendForBody(httpRequest, MemoryCompressionPlanResponseDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS memory compression plan call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS memory compression plan endpoint");
        }
    }

    public List<ConversationTurnDto> fetchConversationHistory(String userId) {
        String encodedUser = encodePathSegment(userId);
        URI uri = baseUri.resolve("/api/chat/" + encodedUser + "/history");

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), new TypeReference<>() {});
            }
            throw parseError(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS history fetch call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS history endpoint");
        }
    }

    public List<Map<String, String>> listSkills() {
        URI uri = baseUri.resolve("/api/skills");

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), new TypeReference<>() {});
            }
            throw parseError(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", "MindOS skill list call interrupted");
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", "Failed to call MindOS skill list endpoint");
        }
    }

    public Map<String, Object> reloadSkills() {
        return postForMap(baseUri.resolve("/api/skills/reload"), Map.of(),
                "MindOS skill reload call interrupted",
                "Failed to call MindOS skill reload endpoint");
    }

    public Map<String, Object> reloadMcpSkills() {
        return postForMap(baseUri.resolve("/api/skills/reload-mcp"), Map.of(),
                "MindOS MCP skill reload call interrupted",
                "Failed to call MindOS MCP skill reload endpoint");
    }

    public Map<String, Object> loadMcpServer(String alias, String url) {
        return loadMcpServer(alias, url, Map.of());
    }

    public Map<String, Object> loadMcpServer(String alias, String url, Map<String, String> headers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alias", alias);
        payload.put("url", url);
        if (headers != null && !headers.isEmpty()) {
            payload.put("headers", headers);
        }
        return postForMap(baseUri.resolve("/api/skills/load-mcp"), payload,
                "MindOS MCP server load call interrupted",
                "Failed to call MindOS MCP server load endpoint");
    }

    public Map<String, Object> loadExternalJar(String url) {
        return postForMap(baseUri.resolve("/api/skills/load-jar"), Map.of(
                        "url", url
                ),
                "MindOS external skill JAR load call interrupted",
                "Failed to call MindOS external skill JAR load endpoint");
    }

    private <T> T sendForBody(HttpRequest request, Class<T> responseClass) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return objectMapper.readValue(response.body(), responseClass);
        }
        throw parseError(response);
    }

    private Map<String, Object> postForMap(URI uri,
                                           Map<String, Object> payload,
                                           String interruptedMessage,
                                           String networkMessage) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), new TypeReference<>() {});
            }
            throw parseError(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantSdkException(0, "INTERRUPTED", interruptedMessage);
        } catch (IOException e) {
            throw new AssistantSdkException(0, "NETWORK_ERROR", networkMessage);
        }
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private AssistantSdkException parseError(HttpResponse<String> response) {
        String body = response.body();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            String code = String.valueOf(parsed.getOrDefault("code", "HTTP_" + response.statusCode()));
            String message = String.valueOf(parsed.getOrDefault("message", "MindOS server returned status " + response.statusCode()));
            return new AssistantSdkException(response.statusCode(), code, message);
        } catch (Exception ignored) {
            return new AssistantSdkException(response.statusCode(), "HTTP_" + response.statusCode(),
                    "MindOS server returned status " + response.statusCode());
        }
    }
}


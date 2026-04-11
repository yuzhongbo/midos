package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class HttpAgentNetworkTransport implements AgentNetworkTransport {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpAgentNetworkTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper().findAndRegisterModules());
    }

    public HttpAgentNetworkTransport(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() : httpClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    @Override
    public AgentTransportKind kind() {
        return AgentTransportKind.HTTP;
    }

    @Override
    public Optional<AgentMessage> send(AgentMessage message, AgentEndpoint endpoint) {
        if (message == null || endpoint == null) {
            return Optional.empty();
        }
        URI uri = endpoint.uri();
        if (uri == null) {
            throw new AgentNetworkException("HTTP endpoint URI is required for " + endpoint.agentId());
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(endpoint.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(message)));
            endpoint.headers().forEach(builder::header);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentNetworkException("HTTP transport failed with status " + response.statusCode()
                        + " for " + endpoint.agentId());
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(objectMapper.readValue(body, AgentMessage.class));
            } catch (Exception parseError) {
                try {
                    Map<String, Object> fallback = objectMapper.readValue(body, new TypeReference<>() {});
                    return Optional.of(new AgentMessage(
                            stringValue(fallback.get("from")),
                            stringValue(fallback.get("to")),
                            stringValue(fallback.get("type")),
                            fallback.get("payload")
                    ));
                } catch (Exception fallbackError) {
                    throw new AgentNetworkException("HTTP response could not be parsed as AgentMessage for " + endpoint.agentId(), fallbackError);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentNetworkException("HTTP agent network interrupted for " + endpoint.agentId(), ex);
        } catch (IOException ex) {
            throw new AgentNetworkException("HTTP agent network I/O failed for " + endpoint.agentId(), ex);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

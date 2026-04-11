package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentEndpoint(String agentId,
                            AgentTransportKind transportKind,
                            URI uri,
                            String queue,
                            Duration timeout,
                            Map<String, String> headers) {

    public AgentEndpoint {
        agentId = normalize(agentId);
        transportKind = transportKind == null ? AgentTransportKind.MQ : transportKind;
        queue = normalize(queue);
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        headers = headers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(headers));
    }

    public static AgentEndpoint http(String agentId, URI uri) {
        return new AgentEndpoint(agentId, AgentTransportKind.HTTP, uri, "", Duration.ofSeconds(5), Map.of());
    }

    public static AgentEndpoint http(String agentId, URI uri, Duration timeout, Map<String, String> headers) {
        return new AgentEndpoint(agentId, AgentTransportKind.HTTP, uri, "", timeout, headers);
    }

    public static AgentEndpoint mq(String agentId, String queue) {
        return new AgentEndpoint(agentId, AgentTransportKind.MQ, null, queue, Duration.ofSeconds(5), Map.of());
    }

    public static AgentEndpoint websocket(String agentId, URI uri) {
        return new AgentEndpoint(agentId, AgentTransportKind.WEBSOCKET, uri, "", Duration.ofSeconds(5), Map.of());
    }

    public static AgentEndpoint websocket(String agentId, URI uri, Duration timeout, Map<String, String> headers) {
        return new AgentEndpoint(agentId, AgentTransportKind.WEBSOCKET, uri, "", timeout, headers);
    }

    public String destination() {
        return queue.isBlank() ? agentId : queue;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultAgentNetwork implements AgentNetwork {

    private final String localAgentId;
    private final AgentNetworkBroker broker;
    private final Map<String, AgentEndpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<AgentTransportKind, AgentNetworkTransport> transports = new EnumMap<>(AgentTransportKind.class);

    public DefaultAgentNetwork(String localAgentId) {
        this(localAgentId, new ObjectMapper().findAndRegisterModules(), AgentNetworkBroker.shared());
    }

    public DefaultAgentNetwork(String localAgentId, ObjectMapper objectMapper, AgentNetworkBroker broker) {
        this(localAgentId, objectMapper, broker, Map.of());
    }

    public DefaultAgentNetwork(String localAgentId,
                               ObjectMapper objectMapper,
                               AgentNetworkBroker broker,
                               Map<AgentTransportKind, AgentNetworkTransport> transportOverrides) {
        String normalizedLocalAgentId = normalize(localAgentId);
        this.localAgentId = normalizedLocalAgentId.isBlank() ? "local-agent" : normalizedLocalAgentId;
        this.broker = broker == null ? AgentNetworkBroker.shared() : broker;
        registerDefaultTransports(objectMapper);
        if (transportOverrides != null) {
            transports.putAll(transportOverrides);
        }
    }

    @Override
    public void registerEndpoint(AgentEndpoint endpoint) {
        if (endpoint == null || endpoint.agentId().isBlank()) {
            throw new AgentNetworkException("Agent endpoint requires a non-blank agentId");
        }
        endpoints.put(endpoint.agentId(), endpoint);
    }

    @Override
    public void send(AgentMessage message) {
        if (message == null) {
            throw new AgentNetworkException("Agent message cannot be null");
        }
        if (message.to().isBlank()) {
            throw new AgentNetworkException("Agent message requires a non-blank recipient");
        }
        AgentEndpoint endpoint = endpoints.get(message.to());
        if (endpoint == null) {
            if (message.to().equalsIgnoreCase(localAgentId)) {
                broker.publish(resolveInbox(message.to()), message);
                return;
            }
            throw new AgentNetworkException("No endpoint registered for recipient " + message.to());
        }
        AgentNetworkTransport transport = transports.get(endpoint.transportKind());
        if (transport == null) {
            throw new AgentNetworkException("No transport registered for " + endpoint.transportKind());
        }
        Optional<AgentMessage> reply = transport.send(message, endpoint);
        reply.ifPresent(this::deliver);
    }

    @Override
    public Optional<AgentMessage> receive() {
        return receive(localAgentId);
    }

    @Override
    public Optional<AgentMessage> receive(String agentId) {
        return broker.poll(resolveInbox(agentId));
    }

    private void deliver(AgentMessage message) {
        if (message == null) {
            return;
        }
        String target = message.to().isBlank() ? localAgentId : message.to();
        broker.publish(resolveInbox(target), message);
    }

    private void registerDefaultTransports(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        transports.put(AgentTransportKind.HTTP, new HttpAgentNetworkTransport(HttpClientFactory.create(), mapper));
        transports.put(AgentTransportKind.MQ, new MqAgentNetworkTransport(broker));
        transports.put(AgentTransportKind.WEBSOCKET, new WebSocketAgentNetworkTransport(null, mapper, Duration.ofSeconds(5)));
    }

    private String resolveInbox(String agentId) {
        String normalized = normalize(agentId);
        return normalized.isBlank() ? localAgentId : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class HttpClientFactory {
        private static java.net.http.HttpClient create() {
            return java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }
    }
}

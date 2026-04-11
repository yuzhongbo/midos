package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import java.util.Optional;

final class MqAgentNetworkTransport implements AgentNetworkTransport {

    private final AgentNetworkBroker broker;

    MqAgentNetworkTransport(AgentNetworkBroker broker) {
        this.broker = broker == null ? AgentNetworkBroker.shared() : broker;
    }

    @Override
    public AgentTransportKind kind() {
        return AgentTransportKind.MQ;
    }

    @Override
    public Optional<AgentMessage> send(AgentMessage message, AgentEndpoint endpoint) {
        if (message == null || endpoint == null) {
            return Optional.empty();
        }
        broker.publish(endpoint.destination(), message);
        return Optional.empty();
    }
}

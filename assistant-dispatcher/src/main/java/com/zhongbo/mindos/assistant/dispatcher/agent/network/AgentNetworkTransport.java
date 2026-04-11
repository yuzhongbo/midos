package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import java.util.Optional;

interface AgentNetworkTransport {

    AgentTransportKind kind();

    Optional<AgentMessage> send(AgentMessage message, AgentEndpoint endpoint);
}

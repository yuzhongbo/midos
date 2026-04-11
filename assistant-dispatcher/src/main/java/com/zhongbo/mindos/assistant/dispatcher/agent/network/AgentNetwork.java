package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import java.util.Optional;

public interface AgentNetwork {

    void registerEndpoint(AgentEndpoint endpoint);

    void send(AgentMessage message);

    Optional<AgentMessage> receive();

    Optional<AgentMessage> receive(String agentId);
}

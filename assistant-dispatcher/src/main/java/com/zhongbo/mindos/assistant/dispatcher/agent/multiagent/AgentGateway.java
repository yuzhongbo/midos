package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

public interface AgentGateway {
    AgentResponse send(AgentMessage message);
}

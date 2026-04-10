package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

public interface Agent {

    String name();

    AgentRole role();

    default AgentResponse plan(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "plan");
    }

    default AgentResponse execute(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "execute");
    }

    default AgentResponse observe(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "observe");
    }

    default AgentResponse handle(AgentMessage message, AgentContext context) {
        AgentTaskType type = message == null ? null : message.type();
        if (type == null) {
            return observe(message, context);
        }
        return switch (type) {
            case PLAN_REQUEST -> plan(message, context);
            case EXECUTE_GRAPH, TOOL_CALL -> execute(message, context);
            case MEMORY_READ, MEMORY_WRITE -> observe(message, context);
        };
    }

    default boolean supports(AgentTaskType type) {
        return true;
    }

    default boolean supports(AgentMessage message) {
        return message != null && supports(message.type());
    }
}

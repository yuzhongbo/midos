package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMessageAdapterTest {

    @Test
    void shouldRoundTripNetworkMessagesIntoAgentTasks() {
        com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage networkMessage =
                com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage.of(
                        "planner-agent",
                        "executor-agent",
                        "PLAN_REQUEST",
                        Map.of(
                                "taskId", "task-1",
                                "userId", "u-1",
                                "userInput", "请规划",
                                "graph", "g1"
                        )
                );

        AgentMessage multiAgentMessage = AgentMessage.fromNetworkMessage(networkMessage);

        assertEquals("planner-agent", multiAgentMessage.from());
        assertEquals("executor-agent", multiAgentMessage.to());
        assertEquals(AgentTaskType.PLAN_REQUEST, multiAgentMessage.type());
        assertEquals("task-1", multiAgentMessage.taskId());
        assertEquals("u-1", multiAgentMessage.userId());
        assertEquals("请规划", multiAgentMessage.userInput());
        assertEquals("g1", multiAgentMessage.payloadMap().get("graph"));

        com.zhongbo.mindos.assistant.dispatcher.agent.network.AgentMessage roundTrip = multiAgentMessage.toNetworkMessage();
        assertEquals("planner-agent", roundTrip.from());
        assertEquals("executor-agent", roundTrip.to());
        assertEquals("PLAN_REQUEST", roundTrip.type());
        assertEquals("task-1", roundTrip.payloadMap().get("taskId"));
        assertEquals("u-1", roundTrip.payloadMap().get("userId"));
        assertEquals("请规划", roundTrip.payloadMap().get("userInput"));
        assertEquals("g1", roundTrip.payloadMap().get("graph"));
    }
}

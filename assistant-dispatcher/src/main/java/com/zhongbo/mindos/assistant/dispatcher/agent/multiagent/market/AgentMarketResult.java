package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.market;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentTaskType;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentResponse;

import java.time.Instant;
import java.util.List;

public record AgentMarketResult(String taskId,
                                AgentTaskType taskType,
                                AgentMessage message,
                                AgentContext context,
                                AgentMarketProposal winner,
                                List<AgentMarketProposal> rankedProposals,
                                Instant evaluatedAt,
                                String summary) {

    public AgentMarketResult {
        taskId = taskId == null ? "" : taskId.trim();
        summary = summary == null ? "" : summary.trim();
        rankedProposals = rankedProposals == null ? List.of() : List.copyOf(rankedProposals);
        evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
    }

    public boolean hasWinner() {
        return winner != null;
    }

    public String winnerAgent() {
        return winner == null ? "" : winner.agentName();
    }

    public AgentResponse winnerResponse() {
        return winner == null ? null : winner.response();
    }

    public static AgentMarketResult empty(AgentMessage message,
                                          AgentContext context,
                                          String reason) {
        return new AgentMarketResult(
                message == null ? "" : message.taskId(),
                message == null ? null : message.type(),
                message,
                context,
                null,
                List.of(),
                Instant.now(),
                reason == null ? "no contestants" : reason
        );
    }
}

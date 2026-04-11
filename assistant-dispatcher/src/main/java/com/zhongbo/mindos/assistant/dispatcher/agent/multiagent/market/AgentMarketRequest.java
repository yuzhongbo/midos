package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.market;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.Agent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public record AgentMarketRequest(AgentMessage message,
                                 AgentContext context,
                                 List<Agent> contestants,
                                 String userFeedback) {

    public AgentMarketRequest {
        message = message == null ? AgentMessage.of("", "", null) : message;
        contestants = normalizeContestants(contestants);
        userFeedback = userFeedback == null ? "" : userFeedback.trim();
    }

    public AgentMarketRequest(AgentMessage message,
                              AgentContext context,
                              Collection<? extends Agent> contestants,
                              String userFeedback) {
        this(message, context, contestants == null ? List.<Agent>of() : new ArrayList<>(contestants), userFeedback);
    }

    public boolean hasContestants() {
        return !contestants.isEmpty();
    }

    private static List<Agent> normalizeContestants(List<Agent> contestants) {
        if (contestants == null || contestants.isEmpty()) {
            return List.of();
        }
        List<Agent> normalized = new ArrayList<>();
        for (Agent contestant : contestants) {
            if (contestant != null) {
                normalized.add(contestant);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }
}

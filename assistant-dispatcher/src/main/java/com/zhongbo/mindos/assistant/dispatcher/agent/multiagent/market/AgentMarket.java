package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.market;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.Agent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage;

import java.util.Collection;

public interface AgentMarket {

    AgentMarketResult compete(AgentMarketRequest request);

    default AgentMarketResult compete(AgentMessage message,
                                      AgentContext context,
                                      Collection<? extends Agent> contestants) {
        return compete(new AgentMarketRequest(message, context, contestants, ""));
    }

    default AgentMarketResult compete(AgentMessage message,
                                      AgentContext context,
                                      Collection<? extends Agent> contestants,
                                      String userFeedback) {
        return compete(new AgentMarketRequest(message, context, contestants, userFeedback));
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.market;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentResponse;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentRole;

import java.util.List;

public record AgentMarketProposal(String agentName,
                                  AgentRole role,
                                  AgentResponse response,
                                  double score,
                                  List<String> reasons) {

    public AgentMarketProposal {
        agentName = agentName == null ? "" : agentName.trim();
        role = role == null ? AgentRole.PLANNER : role;
        score = clamp(score);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean success() {
        return response != null && response.result() != null && response.result().success();
    }

    public boolean terminal() {
        return response != null && response.terminal();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

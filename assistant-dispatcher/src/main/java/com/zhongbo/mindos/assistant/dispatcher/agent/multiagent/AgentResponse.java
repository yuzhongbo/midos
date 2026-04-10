package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentResponse(String agent,
                            SkillResult result,
                            List<AgentMessage> outboundMessages,
                            Map<String, Object> contextPatch,
                            boolean terminal,
                            boolean usedFallback,
                            String summary) {

    public AgentResponse {
        agent = agent == null ? "" : agent.trim();
        outboundMessages = outboundMessages == null ? List.of() : List.copyOf(outboundMessages);
        contextPatch = contextPatch == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(contextPatch));
        summary = summary == null ? "" : summary.trim();
    }

    public static AgentResponse progress(String agent,
                                         String summary,
                                         List<AgentMessage> outboundMessages,
                                         Map<String, Object> contextPatch) {
        return new AgentResponse(agent, null, outboundMessages, contextPatch, false, false, summary);
    }

    public static AgentResponse completed(String agent,
                                          SkillResult result,
                                          boolean usedFallback,
                                          List<AgentMessage> outboundMessages,
                                          Map<String, Object> contextPatch,
                                          String summary) {
        return new AgentResponse(agent, result, outboundMessages, contextPatch, true, usedFallback, summary);
    }

    public static AgentResponse unsupported(String agent, String action) {
        String safeAction = action == null || action.isBlank() ? "operation" : action.trim();
        return completed(
                agent,
                SkillResult.failure(agent, "unsupported " + safeAction),
                false,
                List.of(),
                Map.of(),
                "unsupported " + safeAction
        );
    }
}

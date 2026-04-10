package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;

public record AgentDispatchRequest(Decision decision,
                                   DecisionOrchestrator.OrchestrationRequest orchestrationRequest) {
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

public interface DecisionExecutor {

    DecisionOrchestrator.OrchestrationOutcome execute(Decision decision,
                                                      DecisionOrchestrator.OrchestrationRequest request);
}

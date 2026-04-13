package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.DecisionSignal;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.List;

public interface DecisionPlanner {

    default Decision plan(DecisionOrchestrator.UserInput input) {
        return plan(input, List.of());
    }

    Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals);

    default Decision replan(DecisionOrchestrator.UserInput input, Decision failedDecision) {
        return plan(input);
    }
}

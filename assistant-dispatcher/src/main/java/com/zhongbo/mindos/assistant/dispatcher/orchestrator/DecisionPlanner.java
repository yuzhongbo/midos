package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.DecisionSignal;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.List;

public interface DecisionPlanner {

    Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals);
}

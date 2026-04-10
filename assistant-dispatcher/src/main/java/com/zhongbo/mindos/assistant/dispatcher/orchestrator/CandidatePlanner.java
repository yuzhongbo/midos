package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.List;

public interface CandidatePlanner {

    List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request);

    default List<String> plan(String suggestedTarget) {
        return plan(suggestedTarget, null).stream()
                .map(ScoredCandidate::skillName)
                .toList();
    }
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CandidateChainBuilder {

    private final CandidatePlanner candidatePlanner;
    private final FallbackPlan fallbackPlan;

    CandidateChainBuilder(CandidatePlanner candidatePlanner, FallbackPlan fallbackPlan) {
        this.candidatePlanner = candidatePlanner;
        this.fallbackPlan = fallbackPlan;
    }

    List<ScoredCandidate> build(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
        Map<String, ScoredCandidate> ordered = new LinkedHashMap<>();
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            ordered.put(suggestedTarget, explicitTarget(suggestedTarget));
        }
        candidatePlanner.plan(suggestedTarget, request).forEach(candidate -> ordered.putIfAbsent(candidate.skillName(), candidate));
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            fallbackPlan.fallbacks(suggestedTarget).forEach(fallback ->
                    ordered.putIfAbsent(fallback, configuredFallback(fallback)));
        }
        return List.copyOf(ordered.values());
    }

    ScoredCandidate explicitTarget(String skillName) {
        return new ScoredCandidate(skillName, 1.0, 0.0, 0.0, 0.5, List.of("explicit-target"));
    }

    private ScoredCandidate configuredFallback(String skillName) {
        return new ScoredCandidate(skillName, 0.35, 0.0, 0.0, 0.5, List.of("configured-fallback"));
    }
}

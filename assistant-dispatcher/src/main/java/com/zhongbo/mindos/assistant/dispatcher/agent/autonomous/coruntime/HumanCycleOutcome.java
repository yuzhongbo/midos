package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.util.List;

public record HumanCycleOutcome(SharedDecision decision,
                                HumanFeedback feedback,
                                HumanPreference preference,
                                double trustScore,
                                List<InterventionEvent> interventionEvents,
                                boolean interrupted,
                                boolean correctionApplied) {

    public HumanCycleOutcome {
        feedback = feedback == null ? HumanFeedback.empty() : feedback;
        preference = preference == null ? HumanPreference.defaultPreference() : preference;
        interventionEvents = interventionEvents == null ? List.of() : List.copyOf(interventionEvents);
        trustScore = clamp(trustScore, 0.0);
    }

    public static HumanCycleOutcome empty() {
        return new HumanCycleOutcome(null, HumanFeedback.empty(), HumanPreference.defaultPreference(), 0.0, List.of(), false, false);
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

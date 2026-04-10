package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import java.util.List;
import java.util.Map;

public record SearchCandidate(List<String> path,
                              double score,
                              List<String> reasons,
                              Map<String, Object> metadata) {

    public SearchCandidate {
        path = path == null ? List.of() : List.copyOf(path);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static SearchCandidate from(PlanPath planPath) {
        if (planPath == null) {
            return new SearchCandidate(List.of(), 0.0, List.of(), Map.of());
        }
        return new SearchCandidate(
                planPath.skills(),
                round(planPath.score()),
                planPath.reasons(),
                Map.of("pathCost", round(planPath.pathCost()), "steps", planPath.nodes().size())
        );
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

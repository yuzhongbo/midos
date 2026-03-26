package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record ExecutionTraceDto(
        String strategy,
        int replanCount,
        CritiqueReportDto critique,
        List<PlanStepDto> steps,
        RoutingDecisionDto routing
) {
    public ExecutionTraceDto {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public ExecutionTraceDto(String strategy,
                             int replanCount,
                             CritiqueReportDto critique,
                             List<PlanStepDto> steps) {
        this(strategy, replanCount, critique, steps, null);
    }
}


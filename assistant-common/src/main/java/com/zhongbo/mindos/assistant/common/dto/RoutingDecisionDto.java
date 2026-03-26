package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record RoutingDecisionDto(
        String route,
        String selectedSkill,
        double confidence,
        List<String> reasons,
        List<String> rejectedReasons
) {
    public RoutingDecisionDto {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        rejectedReasons = rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons);
    }
}


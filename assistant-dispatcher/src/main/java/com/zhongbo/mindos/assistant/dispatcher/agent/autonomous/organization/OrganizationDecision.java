package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import java.util.LinkedHashMap;
import java.util.Map;

public record OrganizationDecision(OrganizationDecisionType type,
                                   String targetDepartmentId,
                                   String summary,
                                   int staffingDelta,
                                   String strategyMode,
                                   Map<String, Object> metadata) {

    public OrganizationDecision {
        type = type == null ? OrganizationDecisionType.MAINTAIN : type;
        targetDepartmentId = targetDepartmentId == null ? "" : targetDepartmentId.trim();
        summary = summary == null ? "" : summary.trim();
        strategyMode = strategyMode == null || strategyMode.isBlank() ? "balanced" : strategyMode.trim().toLowerCase(java.util.Locale.ROOT);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static OrganizationDecision maintain(String summary) {
        return new OrganizationDecision(OrganizationDecisionType.MAINTAIN, "", summary, 0, "balanced", Map.of());
    }

    public boolean requiresRestructure() {
        return type != OrganizationDecisionType.MAINTAIN;
    }
}

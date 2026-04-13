package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.util.LinkedHashMap;
import java.util.Map;

public record TransactionRequest(String requesterOrgId,
                                 String providerOrgId,
                                 Map<ResourceType, Double> resourceAmounts,
                                 double totalCost,
                                 double priorityStake,
                                 String purpose,
                                 boolean approvalGranted,
                                 boolean marketMediated,
                                 Map<String, Object> metadata) {

    public TransactionRequest {
        requesterOrgId = requesterOrgId == null ? "" : requesterOrgId.trim();
        providerOrgId = providerOrgId == null ? "" : providerOrgId.trim();
        resourceAmounts = resourceAmounts == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceAmounts));
        totalCost = clamp(totalCost);
        priorityStake = clamp(priorityStake);
        purpose = purpose == null ? "" : purpose.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}

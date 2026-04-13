package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record Transaction(String transactionId,
                          String requesterOrgId,
                          String providerOrgId,
                          Map<ResourceType, Double> resourceAmounts,
                          double totalCost,
                          double priorityStake,
                          boolean approvalGranted,
                          boolean marketMediated,
                          TransactionStatus status,
                          String summary,
                          Instant executedAt) {

    public Transaction {
        transactionId = transactionId == null ? "" : transactionId.trim();
        requesterOrgId = requesterOrgId == null ? "" : requesterOrgId.trim();
        providerOrgId = providerOrgId == null ? "" : providerOrgId.trim();
        resourceAmounts = resourceAmounts == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceAmounts));
        totalCost = clamp(totalCost);
        priorityStake = clamp(priorityStake);
        status = status == null ? TransactionStatus.PENDING : status;
        summary = summary == null ? "" : summary.trim();
        executedAt = executedAt == null ? Instant.now() : executedAt;
    }

    public boolean settled() {
        return status == TransactionStatus.SETTLED;
    }

    public boolean rejected() {
        return status == TransactionStatus.REJECTED;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}

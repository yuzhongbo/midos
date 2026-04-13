package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;

import java.util.LinkedHashMap;
import java.util.Map;

public record CivilizationUnit(String orgId,
                               AIOrganization organization,
                               CapabilityProfile capability,
                               Budget budget,
                               double reputation,
                               Map<ResourceType, Double> resourcePortfolio,
                               boolean active,
                               Map<String, Object> metadata) {

    public CivilizationUnit {
        orgId = orgId == null ? "" : orgId.trim();
        capability = capability == null ? new CapabilityProfile(java.util.List.of(), java.util.List.of(), 0.5, 0.5, 0.5, 1.0, 1.0, Map.of()) : capability;
        budget = budget == null ? new Budget(0.0, 0.0) : budget;
        reputation = clamp(reputation);
        resourcePortfolio = resourcePortfolio == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourcePortfolio));
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public CivilizationUnit withOrganization(AIOrganization nextOrganization) {
        return new CivilizationUnit(orgId, nextOrganization, capability, budget, reputation, resourcePortfolio, active, metadata);
    }

    public CivilizationUnit withBudget(Budget nextBudget) {
        return new CivilizationUnit(orgId, organization, capability, nextBudget, reputation, resourcePortfolio, active, metadata);
    }

    public CivilizationUnit withReputation(double nextReputation) {
        return new CivilizationUnit(orgId, organization, capability, budget, nextReputation, resourcePortfolio, active, metadata);
    }

    public CivilizationUnit withResourcePortfolio(Map<ResourceType, Double> nextResources) {
        return new CivilizationUnit(orgId, organization, capability, budget, reputation, nextResources, active, metadata);
    }

    public CivilizationUnit withActive(boolean nextActive) {
        return new CivilizationUnit(orgId, organization, capability, budget, reputation, resourcePortfolio, nextActive, metadata);
    }

    public CivilizationUnit withMetadata(Map<String, Object> additions) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        if (additions != null) {
            merged.putAll(additions);
        }
        return new CivilizationUnit(orgId, organization, capability, budget, reputation, resourcePortfolio, active, merged);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

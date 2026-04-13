package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CivilizationEvolutionEngine {

    private final CivilizationFactory factory;

    public CivilizationEvolutionEngine(CivilizationFactory factory) {
        this.factory = factory;
    }

    public void evolve(CivilizationState state) {
        if (state == null || state.civilization() == null) {
            return;
        }
        DigitalCivilization civilization = state.civilization();
        CivilizationMemory memory = state.memory();
        double marketLoad = memory == null ? 0.0 : memory.marketLoad();
        double averageSuccess = memory == null ? 0.5 : memory.averageSuccess();
        double rejectionRate = memory == null ? 0.0 : memory.rejectionRate();
        if (civilization.rules() != null) {
            double currentApproval = civilization.rules().threshold(RuleType.HIGH_COST_APPROVAL_REQUIRED, 40.0);
            if (rejectionRate > 0.35) {
                civilization.rules().updateThreshold(RuleType.HIGH_COST_APPROVAL_REQUIRED, Math.min(80.0, currentApproval + 5.0));
            } else if (averageSuccess < 0.45) {
                civilization.rules().updateThreshold(RuleType.HIGH_COST_APPROVAL_REQUIRED, Math.max(25.0, currentApproval - 5.0));
            }
        }
        List<CivilizationUnit> units = new ArrayList<>(civilization.organizations());
        Map<String, Double> reputationMap = new LinkedHashMap<>();
        units.forEach(unit -> reputationMap.put(unit.orgId(), unit.reputation()));
        if (civilization.resources() != null) {
            civilization.resources().redistribute(units, reputationMap);
        }
        units.sort(Comparator.comparingDouble(CivilizationUnit::reputation).reversed());
        if (marketLoad > 0.70 && averageSuccess > 0.55 && units.size() < 5) {
            CivilizationUnit template = units.isEmpty() ? null : units.get(0);
            CivilizationUnit spawned = factory.spawnFrom(template, "org-" + Integer.toUnsignedString((civilization.epoch() + units.size()) * 31), "Frontier Org " + (civilization.epoch() + 1));
            units.add(spawned);
            if (civilization.economy() != null) {
                civilization.economy().registerOrganization(spawned.orgId(), spawned.budget());
            }
            if (civilization.resources() != null) {
                civilization.resources().registerOwner(spawned.orgId(), spawned.resourcePortfolio());
            }
        }
        if (units.size() > 2) {
            for (int index = units.size() - 1; index >= 0; index--) {
                CivilizationUnit unit = units.get(index);
                if (unit == null) {
                    continue;
                }
                if (unit.reputation() < 0.35 && unit.budget().availableCredits() < 60.0) {
                    units.set(index, unit.withActive(false));
                }
            }
        }
        DigitalCivilization nextCivilization = civilization.withOrganizations(List.copyOf(units)).withMetadata(Map.of(
                "marketLoad", round(marketLoad),
                "averageSuccess", round(averageSuccess),
                "rejectionRate", round(rejectionRate)
        ));
        state.civilization(nextCivilization);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

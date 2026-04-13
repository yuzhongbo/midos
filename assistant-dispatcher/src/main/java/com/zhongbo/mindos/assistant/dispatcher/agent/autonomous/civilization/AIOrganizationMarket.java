package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AIOrganizationMarket {

    private final CopyOnWriteArrayList<Offer> offers = new CopyOnWriteArrayList<>();

    public List<Offer> broadcast(TaskRequest request,
                                 List<CivilizationUnit> units,
                                 ResourceSystem resourceSystem,
                                 ReputationSystem reputationSystem) {
        offers.clear();
        if (request == null || units == null || units.isEmpty()) {
            return List.of();
        }
        for (CivilizationUnit unit : units) {
            if (unit == null || !unit.active()) {
                continue;
            }
            if (resourceSystem != null && !resourceSystem.hasResources(unit.orgId(), request.requiredResources())) {
                continue;
            }
            double reputation = reputationSystem == null ? unit.reputation() : reputationSystem.evaluate(unit);
            double capabilityScore = unit.capability().matchScore(request);
            double predictedSuccess = clamp(capabilityScore * 0.50
                    + reputation * 0.25
                    + unit.capability().reliability() * 0.15
                    + unit.capability().executionStrength() * 0.10);
            double quotedCost = unit.capability().quoteCost(request.requiredResources()) * (1.08 - reputation * 0.18);
            double expectedLatency = clamp((1.0 - unit.capability().executionStrength()) * 0.55
                    + unit.capability().latencyMultiplier() * 0.20
                    + (1.0 - reputation) * 0.25);
            offers.add(new Offer(
                    unit.orgId(),
                    capabilityScore,
                    predictedSuccess,
                    quotedCost,
                    expectedLatency,
                    reputation,
                    request.requiredResources(),
                    "offer-from=" + unit.orgId()
            ));
        }
        return offers();
    }

    public Offer match(TaskRequest request) {
        return offers().stream()
                .filter(offer -> request == null || offer.quotedCost() <= request.maxCost() + 1e-9)
                .max(Comparator.comparingDouble(Offer::marketScore)
                        .thenComparingDouble(Offer::predictedSuccess)
                        .thenComparingDouble(Offer::reputationScore))
                .orElse(null);
    }

    public List<Offer> offers() {
        List<Offer> snapshot = new ArrayList<>(offers);
        snapshot.sort(Comparator.comparingDouble(Offer::marketScore).reversed());
        return List.copyOf(snapshot);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

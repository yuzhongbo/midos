package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationCycleResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ReputationSystem {

    private final Map<String, Double> scores = new ConcurrentHashMap<>();

    public double evaluate(AIOrganization organization) {
        if (organization == null) {
            return 0.5;
        }
        Object score = organization.metadata().get("lastKpiHealth");
        if (score instanceof Number number) {
            return clamp(number.doubleValue());
        }
        return 0.5;
    }

    public double evaluate(CivilizationUnit unit) {
        if (unit == null) {
            return 0.5;
        }
        return scores.getOrDefault(unit.orgId(), clamp((unit.reputation() + evaluate(unit.organization())) / 2.0));
    }

    public double update(CivilizationUnit unit,
                         OrgMemory memory,
                         OrganizationCycleResult cycleResult) {
        if (unit == null) {
            return 0.5;
        }
        double historicalSuccess = memory == null ? 0.5 : memory.averageSuccessRate();
        double completion = memory == null ? 0.5 : memory.averageGoalCompletionRate();
        double kpiHealth = cycleResult == null || cycleResult.assessment() == null ? 0.5 : cycleResult.assessment().kpi().healthScore();
        double reputation = clamp(historicalSuccess * 0.35
                + completion * 0.25
                + kpiHealth * 0.25
                + unit.reputation() * 0.15);
        scores.put(unit.orgId(), reputation);
        return reputation;
    }

    public Map<String, Double> scores() {
        return Map.copyOf(scores);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

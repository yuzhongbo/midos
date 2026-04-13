package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrgDecisionEngine {

    public OrganizationDecision decide(Goal goal, KPI kpi) {
        Goal safeGoal = goal == null ? com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal.of("", 0.0) : goal;
        KPI safeKpi = kpi == null ? KPI.empty() : kpi;
        if (safeKpi.successRate() >= 0.65 && safeKpi.goalCompletionRate() < 0.45) {
            return new OrganizationDecision(
                    OrganizationDecisionType.ENHANCE_EVALUATION,
                    "evaluation",
                    "Improve evaluation accuracy for goal: " + safeGoal.description(),
                    1,
                    "audit",
                    Map.of("trigger", "high-success-low-completion")
            );
        }
        if (safeKpi.goalCompletionRate() < 0.45 || safeKpi.successRate() < 0.50) {
            int staffingDelta = safeGoal.priority() >= 0.8 ? 2 : 1;
            return new OrganizationDecision(
                    OrganizationDecisionType.EXPAND_PLANNING,
                    "planning",
                    "Expand planning capacity for goal: " + safeGoal.description(),
                    staffingDelta,
                    "stabilize",
                    Map.of("trigger", "goal-completion-gap")
            );
        }
        if (safeKpi.costEfficiency() < 0.40 && safeKpi.latency() > 0.65) {
            return new OrganizationDecision(
                    OrganizationDecisionType.REDUCE_EXECUTION,
                    "execution",
                    "Reduce execution overhead and tighten retry budget",
                    -1,
                    "lean",
                    Map.of("trigger", "cost-latency-pressure")
            );
        }
        if (safeKpi.healthScore() > 0.78 && safeKpi.costEfficiency() > 0.60) {
            return new OrganizationDecision(
                    OrganizationDecisionType.ADJUST_STRATEGY,
                    "strategy",
                    "Shift organization strategy toward growth and exploration",
                    0,
                    "expand",
                    Map.of("trigger", "healthy-organization")
            );
        }
        if (safeKpi.needsAttention()) {
            return new OrganizationDecision(
                    OrganizationDecisionType.RESTRUCTURE_ORGANIZATION,
                    "organization",
                    "Rebalance organizational structure around current KPI profile",
                    0,
                    "stabilize",
                    Map.of("trigger", "general-attention")
            );
        }
        return OrganizationDecision.maintain("KPI within operating band");
    }
}

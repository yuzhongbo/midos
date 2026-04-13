package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KpiSystem {

    public KPI measure(Goal goal,
                       PlanningOutcome planningOutcome,
                       GoalExecutionResult executionResult,
                       EvaluationResult evaluation,
                       AIOrganization organization,
                       OrgMemory orgMemory) {
        List<OrgMemory.OrgExecutionTrace> recent = orgMemory == null ? List.of() : orgMemory.recent(8);
        double currentSuccess = evaluation == null ? 0.0 : evaluation.isSuccess() ? 1.0 : evaluation.isPartial() ? 0.5 : 0.0;
        double currentEfficiency = planningOutcome == null || planningOutcome.selection() == null || planningOutcome.selection().score() == null
                ? 0.5
                : planningOutcome.selection().score().efficiencyScore();
        double latencyBudgetMs = organization == null || organization.executionDept() == null
                ? 2500.0
                : Math.max(500.0, organization.executionDept().intMetadata("latencyBudgetMs", 2500));
        double currentLatency = executionResult == null ? 1.0 : clamp(executionResult.durationMs() / latencyBudgetMs);
        double currentCompletion = evaluation == null ? 0.0 : evaluation.isSuccess() ? 1.0 : evaluation.progressScore();
        return new KPI(
                blend(currentSuccess, recent.stream().mapToDouble(trace -> trace.success() ? 1.0 : trace.evaluation() != null && trace.evaluation().isPartial() ? 0.5 : 0.0).average().orElse(currentSuccess), recent.size()),
                blend(currentEfficiency, recent.stream().mapToDouble(trace -> trace.kpi() == null ? 0.5 : trace.kpi().costEfficiency()).average().orElse(currentEfficiency), recent.size()),
                blend(currentLatency, recent.stream().mapToDouble(trace -> trace.kpi() == null ? 1.0 : trace.kpi().latency()).average().orElse(currentLatency), recent.size()),
                blend(currentCompletion, recent.stream().mapToDouble(trace -> trace.kpi() == null ? 0.0 : trace.kpi().goalCompletionRate()).average().orElse(currentCompletion), recent.size())
        );
    }

    private double blend(double current, double historical, int historySize) {
        if (historySize <= 0) {
            return clamp(current);
        }
        double weight = Math.min(0.65, 0.30 + historySize * 0.05);
        return clamp(current * (1.0 - weight) + historical * weight);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

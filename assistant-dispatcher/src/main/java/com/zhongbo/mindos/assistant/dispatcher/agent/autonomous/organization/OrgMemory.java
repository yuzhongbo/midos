package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.MultiAgentCoordinator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class OrgMemory {

    private final CopyOnWriteArrayList<OrgExecutionTrace> traces = new CopyOnWriteArrayList<>();

    public OrgExecutionTrace record(AIOrganization organizationBefore,
                                    AIOrganization organizationAfter,
                                    StrategyDirective strategyDirective,
                                    MultiAgentCoordinator.PlanSelection selection,
                                    GoalExecutionResult executionResult,
                                    EvaluationResult evaluation,
                                    KPI kpi,
                                    OrganizationDecision decision) {
        OrgExecutionTrace trace = new OrgExecutionTrace(
                strategyDirective == null || strategyDirective.goal() == null ? "" : strategyDirective.goal().goalId(),
                organizationBefore == null ? "" : organizationBefore.orgName(),
                organizationBefore == null ? 1 : organizationBefore.revision(),
                organizationBefore,
                organizationAfter,
                strategyDirective,
                selection,
                executionResult,
                evaluation,
                kpi == null ? KPI.empty() : kpi,
                decision == null ? OrganizationDecision.maintain("no-decision") : decision,
                Instant.now()
        );
        traces.add(trace);
        return trace;
    }

    public List<OrgExecutionTrace> traces() {
        List<OrgExecutionTrace> snapshot = new ArrayList<>(traces);
        snapshot.sort(Comparator.comparing(OrgExecutionTrace::recordedAt));
        return List.copyOf(snapshot);
    }

    public List<OrgExecutionTrace> recent(int limit) {
        List<OrgExecutionTrace> snapshot = traces();
        if (limit <= 0 || snapshot.size() <= limit) {
            return snapshot;
        }
        return List.copyOf(snapshot.subList(snapshot.size() - limit, snapshot.size()));
    }

    public double averageSuccessRate() {
        return recent(12).stream()
                .mapToDouble(trace -> trace.success() ? 1.0 : trace.evaluation() != null && trace.evaluation().isPartial() ? 0.5 : 0.0)
                .average()
                .orElse(0.5);
    }

    public double averageGoalCompletionRate() {
        return recent(12).stream()
                .mapToDouble(trace -> trace.kpi() == null ? 0.0 : trace.kpi().goalCompletionRate())
                .average()
                .orElse(0.0);
    }

    public double departmentPerformance(DepartmentType departmentType) {
        return recent(12).stream()
                .mapToDouble(trace -> trace.departmentPerformance(departmentType))
                .average()
                .orElse(0.5);
    }

    public double rolePerformance(AgentRole role) {
        return recent(12).stream()
                .mapToDouble(trace -> trace.rolePerformance(role))
                .average()
                .orElse(0.5);
    }

    public record OrgExecutionTrace(String goalId,
                                    String orgName,
                                    int orgRevision,
                                    AIOrganization organizationBefore,
                                    AIOrganization organizationAfter,
                                    StrategyDirective strategyDirective,
                                    MultiAgentCoordinator.PlanSelection planSelection,
                                    GoalExecutionResult executionResult,
                                    EvaluationResult evaluation,
                                    KPI kpi,
                                    OrganizationDecision decision,
                                    Instant recordedAt) {

        public OrgExecutionTrace {
            goalId = goalId == null ? "" : goalId.trim();
            orgName = orgName == null ? "" : orgName.trim();
            orgRevision = Math.max(1, orgRevision);
            recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        }

        public boolean success() {
            return evaluation != null && evaluation.isSuccess();
        }

        public double predictionAccuracy() {
            if (planSelection == null || planSelection.prediction() == null) {
                return 0.5;
            }
            double predicted = planSelection.prediction().successProbability();
            double actual = evaluation == null ? (executionResult != null && executionResult.success() ? 1.0 : 0.0) : evaluation.progressScore();
            return clamp(1.0 - Math.abs(actual - predicted));
        }

        public double departmentPerformance(DepartmentType type) {
            if (type == null) {
                return 0.5;
            }
            return switch (type) {
                case STRATEGY -> kpi == null ? 0.5 : kpi.healthScore();
                case PLANNING -> planSelection == null || planSelection.score() == null ? 0.5 : planSelection.score().score();
                case EXECUTION -> clamp((success() ? 1.0 : 0.25)
                        + (kpi == null ? 0.0 : kpi.goalCompletionRate() * 0.35)
                        - (kpi == null ? 0.0 : kpi.latency() * 0.35));
                case EVALUATION -> predictionAccuracy();
            };
        }

        public double rolePerformance(AgentRole role) {
            if (role == null) {
                return 0.5;
            }
            return switch (role) {
                case STRATEGIST -> departmentPerformance(DepartmentType.STRATEGY);
                case PLANNER -> departmentPerformance(DepartmentType.PLANNING);
                case EXECUTOR -> departmentPerformance(DepartmentType.EXECUTION);
                case ANALYST -> departmentPerformance(DepartmentType.EVALUATION);
                case OPTIMIZER -> clamp((departmentPerformance(DepartmentType.STRATEGY)
                        + departmentPerformance(DepartmentType.EVALUATION)) / 2.0);
            };
        }

        private static double clamp(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }
    }
}

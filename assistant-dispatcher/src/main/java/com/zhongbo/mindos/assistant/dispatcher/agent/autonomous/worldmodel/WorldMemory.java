package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class WorldMemory {

    private final CopyOnWriteArrayList<ExecutionTrace> traces = new CopyOnWriteArrayList<>();

    public ExecutionTrace record(Goal goal,
                                 MultiAgentCoordinator.PlanSelection selection,
                                 GoalExecutionResult result,
                                 EvaluationResult evaluation) {
        if (selection == null) {
            return null;
        }
        double actual = evaluation == null ? (result != null && result.success() ? 1.0 : 0.0) : evaluation.progressScore();
        double predicted = selection.prediction() == null ? 0.0 : selection.prediction().successProbability();
        ExecutionTrace trace = new ExecutionTrace(
                goal == null ? "" : goal.goalId(),
                selection.agentId(),
                selection.strategyType(),
                selection.graph(),
                selection.prediction(),
                result,
                evaluation,
                round(actual - predicted),
                selection.score() == null ? 0.0 : selection.score().score(),
                Instant.now()
        );
        traces.add(trace);
        return trace;
    }

    public List<ExecutionTrace> traces() {
        List<ExecutionTrace> snapshot = new ArrayList<>(traces);
        snapshot.sort(Comparator.comparing(ExecutionTrace::recordedAt));
        return List.copyOf(snapshot);
    }

    public List<ExecutionTrace> tracesForAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return List.of();
        }
        return traces().stream()
                .filter(trace -> agentId.equalsIgnoreCase(trace.agentId()))
                .toList();
    }

    public double averageErrorGap(String agentId) {
        List<ExecutionTrace> agentTraces = tracesForAgent(agentId);
        if (agentTraces.isEmpty()) {
            return 0.0;
        }
        return agentTraces.stream()
                .mapToDouble(ExecutionTrace::errorGap)
                .average()
                .orElse(0.0);
    }

    public double averageErrorGapForTarget(String target) {
        if (target == null || target.isBlank()) {
            return 0.0;
        }
        List<ExecutionTrace> matched = traces().stream()
                .filter(trace -> trace.plan() != null
                        && trace.plan().nodes().stream().anyMatch(node -> node != null && target.equalsIgnoreCase(node.target())))
                .toList();
        if (matched.isEmpty()) {
            return 0.0;
        }
        return matched.stream()
                .mapToDouble(ExecutionTrace::errorGap)
                .average()
                .orElse(0.0);
    }

    public Map<String, Double> latestAgentWeights(StrategyEvolutionEngine evolutionEngine) {
        if (evolutionEngine == null) {
            return Map.of();
        }
        return evolutionEngine.profiles().values().stream()
                .collect(java.util.stream.Collectors.toMap(
                        StrategyEvolutionEngine.StrategyProfile::agentId,
                        StrategyEvolutionEngine.StrategyProfile::weight
                ));
    }

    public record ExecutionTrace(String goalId,
                                 String agentId,
                                 String strategyType,
                                 TaskGraph plan,
                                 PredictionResult prediction,
                                 GoalExecutionResult result,
                                 EvaluationResult evaluation,
                                 double errorGap,
                                 double evaluatedScore,
                                 Instant recordedAt) {

        public ExecutionTrace {
            goalId = goalId == null ? "" : goalId.trim();
            agentId = agentId == null ? "" : agentId.trim();
            strategyType = normalize(strategyType);
            evaluatedScore = clamp(evaluatedScore);
            recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        }

        public boolean success() {
            return evaluation != null ? evaluation.isSuccess() : result != null && result.success();
        }

        public double actualScore() {
            if (evaluation != null) {
                return evaluation.progressScore();
            }
            return result != null && result.success() ? 1.0 : 0.0;
        }

        public List<String> targets() {
            if (plan == null || plan.nodes() == null) {
                return List.of();
            }
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            plan.nodes().forEach(node -> {
                if (node != null && node.target() != null && !node.target().isBlank()) {
                    targets.add(node.target());
                }
            });
            return targets.isEmpty() ? List.of() : List.copyOf(targets);
        }

        private static String normalize(String value) {
            if (value == null) {
                return "balanced";
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? "balanced" : normalized;
        }

        private static double clamp(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

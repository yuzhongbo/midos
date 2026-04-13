package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class GoalMemory {

    private final Map<String, CopyOnWriteArrayList<GoalTrace>> tracesByGoal = new ConcurrentHashMap<>();

    public GoalTrace record(GoalTrace trace) {
        if (trace == null || trace.goal() == null || trace.goal().goalId().isBlank()) {
            return trace;
        }
        tracesByGoal.computeIfAbsent(trace.goal().goalId(), ignored -> new CopyOnWriteArrayList<>()).add(trace);
        return trace;
    }

    public List<GoalTrace> traces(String goalId) {
        if (goalId == null || goalId.isBlank()) {
            return List.of();
        }
        return List.copyOf(tracesByGoal.getOrDefault(goalId, new CopyOnWriteArrayList<>()));
    }

    public List<GoalTrace> allTraces() {
        List<GoalTrace> traces = new ArrayList<>();
        tracesByGoal.values().forEach(traces::addAll);
        traces.sort(Comparator.comparing(GoalTrace::recordedAt));
        return List.copyOf(traces);
    }

    public Optional<GoalTrace> latest(String goalId) {
        return traces(goalId).stream()
                .max(Comparator.comparing(GoalTrace::recordedAt));
    }

    public int iterationCount(String goalId) {
        return traces(goalId).size();
    }

    public Set<String> completedTaskIds(String goalId) {
        LinkedHashSet<String> completed = new LinkedHashSet<>();
        for (GoalTrace trace : traces(goalId)) {
            if (trace == null || trace.evaluation() == null) {
                continue;
            }
            completed.addAll(trace.evaluation().completedTaskIds());
        }
        return completed.isEmpty() ? Set.of() : Set.copyOf(completed);
    }

    public List<String> failedTargets(String goalId) {
        LinkedHashSet<String> failedTargets = new LinkedHashSet<>();
        for (GoalTrace trace : traces(goalId)) {
            if (trace == null || trace.evaluation() == null) {
                continue;
            }
            failedTargets.addAll(trace.evaluation().failedTargets());
        }
        return failedTargets.isEmpty() ? List.of() : List.copyOf(failedTargets);
    }

    public Optional<TaskGraph> latestSuccessfulGraph(Goal goal) {
        if (goal == null || goal.description().isBlank()) {
            return Optional.empty();
        }
        String signature = signature(goal.description());
        return allTraces().stream()
                .filter(trace -> trace != null
                        && trace.goal() != null
                        && signature(trace.goal().description()).equals(signature)
                        && trace.evaluation() != null
                        && trace.evaluation().isSuccess()
                        && trace.graph() != null
                        && !trace.graph().isEmpty())
                .max(Comparator.comparing(GoalTrace::recordedAt))
                .map(GoalTrace::graph);
    }

    private String signature(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        return description.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public record GoalTrace(Goal goal,
                            TaskGraph graph,
                            GoalExecutionResult result,
                            EvaluationResult evaluation,
                            int iteration,
                            Instant recordedAt) {

        public GoalTrace {
            iteration = Math.max(1, iteration);
            recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        }
    }
}

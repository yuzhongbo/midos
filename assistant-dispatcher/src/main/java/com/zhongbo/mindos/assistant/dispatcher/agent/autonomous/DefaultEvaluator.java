package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class DefaultEvaluator implements Evaluator {

    @Override
    public EvaluationResult evaluate(GoalExecutionResult result, Goal goal) {
        Goal safeGoal = goal == null ? (result == null ? Goal.of("", 0.0) : result.goal()) : goal;
        TaskGraph graph = result == null || result.graph() == null ? new TaskGraph(List.of(), List.of()) : result.graph();
        List<String> requiredNodeIds = graph.nodes().stream()
                .filter(node -> node != null && !node.optional())
                .map(TaskNode::id)
                .toList();
        List<String> completedTaskIds = result == null || result.graphResult() == null
                ? List.of()
                : result.graphResult().successfulNodeIds().stream()
                .filter(requiredNodeIds::contains)
                .toList();
        List<String> remainingTaskIds = requiredNodeIds.stream()
                .filter(nodeId -> !completedTaskIds.contains(nodeId))
                .toList();
        LinkedHashSet<String> failedTargets = new LinkedHashSet<>();
        if (result != null) {
            failedTargets.addAll(result.failedTargets());
        }
        boolean success = !requiredNodeIds.isEmpty()
                && remainingTaskIds.isEmpty()
                && result != null
                && result.success();
        boolean partial = !success && !completedTaskIds.isEmpty();
        boolean requiresReplan = !success && (!remainingTaskIds.isEmpty() || !failedTargets.isEmpty());
        GoalStatus goalStatus = success
                ? GoalStatus.COMPLETED
                : requiresReplan
                ? GoalStatus.IN_PROGRESS
                : GoalStatus.FAILED;
        double progressScore = requiredNodeIds.isEmpty()
                ? (success ? 1.0 : 0.0)
                : completedTaskIds.size() / (double) requiredNodeIds.size();
        String summary = success
                ? firstNonBlank(result == null || result.finalResult() == null ? "" : result.finalResult().output(), "goal completed")
                : firstNonBlank(
                result == null || result.finalResult() == null ? "" : result.finalResult().output(),
                remainingTaskIds.isEmpty() ? "goal failed" : "goal needs replanning"
        );
        return new EvaluationResult(
                safeGoal == null ? "" : safeGoal.goalId(),
                goalStatus,
                success,
                partial,
                requiresReplan,
                summary,
                completedTaskIds,
                remainingTaskIds,
                List.copyOf(failedTargets),
                progressScore,
                Instant.now()
        );
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}

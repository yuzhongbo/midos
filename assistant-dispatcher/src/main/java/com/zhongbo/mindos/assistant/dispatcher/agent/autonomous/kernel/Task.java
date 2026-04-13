package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record Task(String taskId,
                   Goal goal,
                   TaskGraph graph,
                   ExecutionPolicy policy,
                   Map<String, Object> metadata) {

    public Task {
        goal = goal == null ? Goal.of("", 0.0) : goal;
        taskId = normalizeTaskId(taskId, goal);
        graph = graph == null ? new TaskGraph(java.util.List.of(), java.util.List.of()) : graph;
        policy = policy == null ? ExecutionPolicy.AUTONOMOUS : policy;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static Task fromGoal(Goal goal,
                                ExecutionPolicy policy,
                                Map<String, Object> metadata) {
        return new Task("", goal, new TaskGraph(java.util.List.of(), java.util.List.of()), policy, metadata);
    }

    public boolean hasGraph() {
        return graph != null && !graph.isEmpty();
    }

    private static String normalizeTaskId(String taskId, Goal goal) {
        if (taskId != null && !taskId.isBlank()) {
            return taskId.trim();
        }
        if (goal != null && goal.goalId() != null && !goal.goalId().isBlank()) {
            return "task:" + goal.goalId();
        }
        return "task:" + UUID.randomUUID();
    }
}

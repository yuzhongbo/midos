package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExecutionPlan(Task task,
                            TaskGraph graph,
                            ExecutionPolicy policy,
                            CognitiveModule modules,
                            Map<CognitiveCapability, String> assignedPlugins,
                            List<RuntimeObject> runtimeObjects,
                            Map<RuntimeResourceType, Double> resourceAllocation,
                            Node executionNode,
                            Map<String, Object> attributes,
                            String summary,
                            Instant plannedAt) {

    public ExecutionPlan {
        task = task == null ? Task.fromGoal(null, ExecutionPolicy.AUTONOMOUS, Map.of()) : task;
        graph = graph == null ? new TaskGraph(List.of(), List.of()) : graph;
        policy = policy == null ? task.policy() : policy;
        assignedPlugins = assignedPlugins == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(assignedPlugins));
        runtimeObjects = runtimeObjects == null ? List.of() : List.copyOf(runtimeObjects);
        resourceAllocation = resourceAllocation == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(resourceAllocation));
        executionNode = executionNode == null ? Node.local() : executionNode;
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        summary = summary == null ? "" : summary.trim();
        plannedAt = plannedAt == null ? Instant.now() : plannedAt;
    }

    public boolean executable() {
        return graph != null && !graph.isEmpty();
    }
}

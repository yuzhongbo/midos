package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskGraphExecutor {

    public TaskGraphExecutionResult execute(TaskGraph graph,
                                            SkillContext baseContext,
                                            TaskNodeRunner runner) {
        if (graph == null || graph.isEmpty()) {
            return new TaskGraphExecutionResult(null, List.of(), baseContext == null ? Map.of() : baseContext.attributes(), List.of());
        }
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) {
            byId.put(node.id(), node);
            indegree.put(node.id(), node.dependsOn().size());
            dependents.putIfAbsent(node.id(), new ArrayList<>());
        }
        for (TaskNode node : graph.nodes()) {
            for (String dependency : node.dependsOn()) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node.id());
            }
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.addLast(id);
            }
        });
        if (ready.isEmpty()) {
            throw new IllegalArgumentException("TaskGraph contains a cycle or no executable root node");
        }

        Map<String, Object> contextAttributes = new LinkedHashMap<>(baseContext == null ? Map.of() : baseContext.attributes());
        List<TaskGraphExecutionResult.NodeResult> nodeResults = new ArrayList<>();
        List<String> executionOrder = new ArrayList<>();
        Map<String, TaskGraphExecutionResult.NodeResult> resultsById = new LinkedHashMap<>();
        SkillResult finalResult = null;

        while (!ready.isEmpty()) {
            String nodeId = ready.removeFirst();
            TaskNode node = byId.get(nodeId);
            if (node == null) {
                continue;
            }
            executionOrder.add(nodeId);
            boolean blocked = node.dependsOn().stream().anyMatch(dependency -> {
                TaskGraphExecutionResult.NodeResult dependencyResult = resultsById.get(dependency);
                return dependencyResult == null || dependencyResult.result() == null || !dependencyResult.result().success();
            });
            TaskGraphExecutionResult.NodeResult nodeResult;
            if (blocked) {
                nodeResult = new TaskGraphExecutionResult.NodeResult(
                        node.id(),
                        node.target(),
                        "blocked",
                        SkillResult.failure(node.target(), "blocked by dependency"),
                        false
                );
            } else {
                Map<String, Object> resolvedParams = resolveParams(node.params(), contextAttributes);
                SkillContext nodeContext = new SkillContext(
                        baseContext == null ? "" : baseContext.userId(),
                        baseContext == null ? "" : baseContext.input(),
                        mergeAttributes(contextAttributes, resolvedParams)
                );
                NodeExecution execution = runner.run(node, nodeContext);
                finalResult = execution.result();
                nodeResult = new TaskGraphExecutionResult.NodeResult(
                        node.id(),
                        node.target(),
                        execution.result() != null && execution.result().success() ? "success" : "failed",
                        execution.result(),
                        execution.usedFallback()
                );
                if (execution.result() != null) {
                    contextAttributes.put("task.last.output", execution.result().output());
                    contextAttributes.put("task.last.skill", execution.result().skillName());
                    contextAttributes.put("task.last.success", execution.result().success());
                    contextAttributes.put("task." + node.id() + ".output", execution.result().output());
                    contextAttributes.put("task." + node.id() + ".skill", execution.result().skillName());
                    contextAttributes.put("task." + node.id() + ".success", execution.result().success());
                    if (!node.saveAs().isBlank()) {
                        contextAttributes.put("task." + node.saveAs() + ".output", execution.result().output());
                        contextAttributes.put("task." + node.saveAs() + ".skill", execution.result().skillName());
                        contextAttributes.put("task." + node.saveAs() + ".success", execution.result().success());
                    }
                }
            }
            resultsById.put(node.id(), nodeResult);
            nodeResults.add(nodeResult);
            for (String dependent : dependents.getOrDefault(node.id(), List.of())) {
                indegree.computeIfPresent(dependent, (ignored, degree) -> degree - 1);
                if (indegree.getOrDefault(dependent, 0) == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        if (nodeResults.size() != graph.nodes().size()) {
            throw new IllegalArgumentException("TaskGraph contains a cycle");
        }
        TaskGraphExecutionResult.NodeResult last = nodeResults.isEmpty() ? null : nodeResults.get(nodeResults.size() - 1);
        return new TaskGraphExecutionResult(
                last == null ? finalResult : last.result(),
                nodeResults,
                Map.copyOf(contextAttributes),
                executionOrder
        );
    }

    private Map<String, Object> resolveParams(Map<String, Object> rawParams, Map<String, Object> contextAttributes) {
        if (rawParams == null || rawParams.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        rawParams.forEach((key, value) -> resolved.put(key, resolveValue(value, contextAttributes)));
        return Map.copyOf(resolved);
    }

    private Object resolveValue(Object value, Map<String, Object> contextAttributes) {
        if (!(value instanceof String text) || text.isBlank()) {
            return value;
        }
        String resolved = text;
        for (Map.Entry<String, Object> entry : contextAttributes.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return resolved;
    }

    private Map<String, Object> mergeAttributes(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        if (extra != null) {
            merged.putAll(extra);
        }
        return Map.copyOf(merged);
    }

    @FunctionalInterface
    public interface TaskNodeRunner {
        NodeExecution run(TaskNode node, SkillContext nodeContext);
    }

    public record NodeExecution(SkillResult result, boolean usedFallback) {
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DAGExecutor {

    public TaskGraphExecutionResult execute(TaskGraph graph,
                                            SkillContext baseContext,
                                            TaskNodeRunner runner) {
        if (graph == null || graph.isEmpty()) {
            return new TaskGraphExecutionResult(null, List.of(), baseContext == null ? Map.of() : baseContext.attributes(), List.of());
        }
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) {
            byId.put(node.id(), node);
            indegree.put(node.id(), graph.dependenciesOf(node.id()).size());
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

        // Use concurrent maps/lists for parallel execution
        Map<String, Object> contextAttributes = new java.util.concurrent.ConcurrentHashMap<>(baseContext == null ? Map.of() : baseContext.attributes());
        List<TaskGraphExecutionResult.NodeResult> nodeResults = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<String, TaskGraphExecutionResult.NodeResult> resultsById = new java.util.concurrent.ConcurrentHashMap<>();
        SkillResult finalResult = null;

        while (!ready.isEmpty()) {
            List<String> batch = new ArrayList<>();
            while (!ready.isEmpty()) {
                batch.add(ready.removeFirst());
            }
            // Execute all ready nodes in parallel
            List<CompletableFuture<TaskGraphExecutionResult.NodeResult>> futures = new ArrayList<>();
            for (String nodeId : batch) {
                TaskNode node = byId.get(nodeId);
                if (node == null) {
                    continue;
                }
                CompletableFuture<TaskGraphExecutionResult.NodeResult> future = CompletableFuture.supplyAsync(() -> {
                    boolean blocked = graph.dependenciesOf(node.id()).stream().anyMatch(dependency -> {
                        TaskGraphExecutionResult.NodeResult dependencyResult = resultsById.get(dependency);
                        return dependencyResult == null || dependencyResult.result() == null || !dependencyResult.result().success();
                    });
                    if (blocked) {
                        TaskGraphExecutionResult.NodeResult nr = new TaskGraphExecutionResult.NodeResult(
                                node.id(), node.target(), "blocked", SkillResult.failure(node.target(), "blocked by dependency"), false
                        );
                        resultsById.put(node.id(), nr);
                        return nr;
                    }

                    // snapshot context attributes to avoid races when resolving params
                    Map<String, Object> contextSnapshot;
                    synchronized (contextAttributes) {
                        contextSnapshot = new java.util.LinkedHashMap<>(contextAttributes);
                    }
                    Map<String, Object> resolvedParams = resolveParams(node.params(), contextSnapshot);
                    SkillContext nodeContext = new SkillContext(
                            baseContext == null ? "" : baseContext.userId(),
                            baseContext == null ? "" : baseContext.input(),
                            mergeAttributes(contextSnapshot, resolvedParams)
                    );
                    NodeExecution execution = runner.run(node, nodeContext);
                    SkillResult sr = execution.result();
                    TaskGraphExecutionResult.NodeResult nr = new TaskGraphExecutionResult.NodeResult(
                            node.id(), node.target(), (sr != null && sr.success()) ? "success" : "failed", sr, execution.usedFallback()
                    );

                    // Update shared context attributes atomically
                    if (sr != null) {
                        synchronized (contextAttributes) {
                            contextAttributes.put("task.last.output", sr.output());
                            contextAttributes.put("task.last.skill", sr.skillName());
                            contextAttributes.put("task.last.success", sr.success());
                            contextAttributes.put("task." + node.id() + ".output", sr.output());
                            contextAttributes.put("task." + node.id() + ".skill", sr.skillName());
                            contextAttributes.put("task." + node.id() + ".success", sr.success());
                            if (!node.saveAs().isBlank()) {
                                contextAttributes.put("task." + node.saveAs() + ".output", sr.output());
                                contextAttributes.put("task." + node.saveAs() + ".skill", sr.skillName());
                                contextAttributes.put("task." + node.saveAs() + ".success", sr.success());
                            }
                        }
                    }
                    resultsById.put(node.id(), nr);
                    return nr;
                });
                futures.add(future);
            }

            // Wait for batch completion and collect results
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<TaskGraphExecutionResult.NodeResult> f : futures) {
                try {
                    TaskGraphExecutionResult.NodeResult nr = f.get();
                    nodeResults.add(nr);
                    executionOrder.add(nr.nodeId());
                    if (nr.result() != null) {
                        finalResult = nr.result();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to execute task node", e);
                }
            }

            // Decrease indegree for dependents and enqueue newly ready nodes
            for (String nodeId : batch) {
                for (String dependent : graph.dependentsOf(nodeId)) {
                    indegree.computeIfPresent(dependent, (ignored, degree) -> degree - 1);
                    if (indegree.getOrDefault(dependent, 0) == 0) {
                        ready.addLast(dependent);
                    }
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

    protected Map<String, Object> resolveParams(Map<String, Object> rawParams, Map<String, Object> contextAttributes) {
        if (rawParams == null || rawParams.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        rawParams.forEach((key, value) -> resolved.put(key, resolveValue(value, contextAttributes)));
        return Map.copyOf(resolved);
    }

    protected Object resolveValue(Object value, Map<String, Object> contextAttributes) {
        if (!(value instanceof String text) || text.isBlank()) {
            return value;
        }
        String resolved = text;
        for (Map.Entry<String, Object> entry : contextAttributes.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return resolved;
    }

    protected Map<String, Object> mergeAttributes(Map<String, Object> base, Map<String, Object> extra) {
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

package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record TaskGraph(List<TaskNode> nodes, List<TaskEdge> edges) {

    public TaskGraph(List<TaskNode> nodes) {
        this(nodes, edgesFromNodes(nodes));
    }

    public TaskGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = mergeEdges(nodes, edges);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public static TaskGraph linear(List<String> path, Map<String, Object> sharedParams) {
        if (path == null || path.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
        List<TaskEdge> edges = new ArrayList<>();
        String previousId = null;
        int index = 1;
        for (String target : path) {
            String nodeId = "node-" + index;
            nodes.add(new TaskNode(
                    nodeId,
                    target,
                    index == 1 ? (sharedParams == null ? Map.of() : sharedParams) : Map.of(),
                    previousId == null ? List.of() : List.of(previousId),
                    "step" + index,
                    false
            ));
            if (previousId != null) {
                edges.add(new TaskEdge(previousId, nodeId));
            }
            previousId = nodeId;
            index++;
        }
        return new TaskGraph(nodes, edges);
    }

    public static TaskGraph fromTasks(List<Map<String, Object>> rawTasks) {
        if (rawTasks == null || rawTasks.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
        List<TaskEdge> edges = new ArrayList<>();
        for (Map<String, Object> rawTask : rawTasks) {
            if (rawTask == null) {
                continue;
            }
            String id = stringValue(rawTask.get("id"));
            String target = stringValue(rawTask.get("target"));
            if (target.isBlank()) {
                continue;
            }
            Map<String, Object> params = rawTask.get("params") instanceof Map<?, ?> rawParams
                    ? rawParams.entrySet().stream().collect(LinkedHashMap::new,
                    (acc, entry) -> acc.put(String.valueOf(entry.getKey()), entry.getValue()),
                    LinkedHashMap::putAll)
                    : Map.of();
            List<String> dependsOn = rawTask.get("dependsOn") instanceof List<?> rawDepends
                    ? rawDepends.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList()
                    : List.of();
            TaskNode node = new TaskNode(
                    id,
                    target,
                    params,
                    dependsOn,
                    stringValue(rawTask.get("saveAs")),
                    Boolean.parseBoolean(String.valueOf(rawTask.getOrDefault("optional", false)))
            );
            nodes.add(node);
            for (String dependency : dependsOn) {
                edges.add(new TaskEdge(dependency, node.id()));
            }
        }
        return new TaskGraph(nodes, edges);
    }

    public static TaskGraph fromDsl(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        Object rawNodes = params.get("nodes");
        if (!(rawNodes instanceof List<?> nodeValues)) {
            rawNodes = params.get("steps");
        }
        if (!(rawNodes instanceof List<?>)) {
            rawNodes = params.get("tasks");
        }
        if (!(rawNodes instanceof List<?> nodeEntries)) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
        List<TaskEdge> edges = new ArrayList<>();
        for (Object entry : nodeEntries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            String id = stringValue(normalized.get("id"));
            String target = stringValue(normalized.get("target"));
            if (target.isBlank()) {
                continue;
            }
            Map<String, Object> nodeParams = normalized.get("params") instanceof Map<?, ?> rawParams
                    ? rawParams.entrySet().stream()
                    .collect(LinkedHashMap::new,
                            (acc, item) -> acc.put(String.valueOf(item.getKey()), item.getValue()),
                            LinkedHashMap::putAll)
                    : Map.of();
            List<String> dependsOn = normalized.get("dependsOn") instanceof List<?> rawDepends
                    ? rawDepends.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList()
                    : List.of();
            TaskNode node = new TaskNode(
                    id,
                    target,
                    nodeParams,
                    dependsOn,
                    stringValue(normalized.get("saveAs")),
                    Boolean.parseBoolean(String.valueOf(normalized.getOrDefault("optional", false)))
            );
            nodes.add(node);
            for (String dependency : dependsOn) {
                edges.add(new TaskEdge(dependency, node.id()));
            }
        }
        if (params.get("edges") instanceof List<?> rawEdges) {
            for (Object entry : rawEdges) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                String from = stringValue(map.get("from"));
                String to = stringValue(map.get("to"));
                if (!from.isBlank() && !to.isBlank()) {
                    edges.add(new TaskEdge(from, to));
                }
            }
        }
        return new TaskGraph(nodes, edges);
    }

    public List<String> dependenciesOf(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return List.of();
        }
        return edges.stream()
                .filter(edge -> nodeId.equals(edge.to()))
                .map(TaskEdge::from)
                .distinct()
                .toList();
    }

    public List<String> dependentsOf(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return List.of();
        }
        return edges.stream()
                .filter(edge -> nodeId.equals(edge.from()))
                .map(TaskEdge::to)
                .distinct()
                .toList();
    }

    private static List<TaskEdge> mergeEdges(List<TaskNode> nodes, List<TaskEdge> explicitEdges) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<TaskEdge> merged = new ArrayList<>();
        for (TaskEdge edge : edgesFromNodes(nodes)) {
            if (seen.add(edge.from() + "->" + edge.to())) {
                merged.add(edge);
            }
        }
        if (explicitEdges != null) {
            for (TaskEdge edge : explicitEdges) {
                if (edge == null || edge.from().isBlank() || edge.to().isBlank()) {
                    continue;
                }
                if (seen.add(edge.from() + "->" + edge.to())) {
                    merged.add(edge);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static List<TaskEdge> edgesFromNodes(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<TaskEdge> edges = new ArrayList<>();
        for (TaskNode node : nodes) {
            if (node == null) {
                continue;
            }
            for (String dependency : node.dependsOn()) {
                if (dependency != null && !dependency.isBlank()) {
                    edges.add(new TaskEdge(dependency, node.id()));
                }
            }
        }
        return List.copyOf(edges);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TaskGraph(List<TaskNode> nodes) {

    public TaskGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public static TaskGraph linear(List<String> path, Map<String, Object> sharedParams) {
        if (path == null || path.isEmpty()) {
            return new TaskGraph(List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
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
            previousId = nodeId;
            index++;
        }
        return new TaskGraph(nodes);
    }

    public static TaskGraph fromTasks(List<Map<String, Object>> rawTasks) {
        if (rawTasks == null || rawTasks.isEmpty()) {
            return new TaskGraph(List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
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
            nodes.add(new TaskNode(
                    id,
                    target,
                    params,
                    dependsOn,
                    stringValue(rawTask.get("saveAs")),
                    Boolean.parseBoolean(String.valueOf(rawTask.getOrDefault("optional", false)))
            ));
        }
        return new TaskGraph(nodes);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

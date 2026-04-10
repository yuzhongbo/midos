package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ExecutionSnapshot(String traceId,
                                String userId,
                                TaskGraph graph,
                                TaskGraphExecutionResult result,
                                Map<String, Object> contextAttributes,
                                List<NodeSnapshot> nodes,
                                List<String> executionOrder,
                                List<String> successfulNodeIds,
                                List<String> failedNodeIds,
                                List<String> blockedNodeIds,
                                String failureSummary,
                                Instant capturedAt) {

    public ExecutionSnapshot {
        traceId = normalize(traceId);
        userId = normalize(userId);
        graph = graph == null ? new TaskGraph(List.of(), List.of()) : graph;
        contextAttributes = contextAttributes == null ? Map.of() : Map.copyOf(contextAttributes);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        executionOrder = executionOrder == null ? List.of() : List.copyOf(executionOrder);
        successfulNodeIds = successfulNodeIds == null ? List.of() : List.copyOf(successfulNodeIds);
        failedNodeIds = failedNodeIds == null ? List.of() : List.copyOf(failedNodeIds);
        blockedNodeIds = blockedNodeIds == null ? List.of() : List.copyOf(blockedNodeIds);
        failureSummary = normalize(failureSummary);
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    public static ExecutionSnapshot from(String traceId,
                                         String userId,
                                         TaskGraph graph,
                                         TaskGraphExecutionResult result,
                                         Map<String, Object> contextAttributes) {
        Map<String, TaskNode> nodeById = new LinkedHashMap<>();
        if (graph != null) {
            for (TaskNode node : graph.nodes()) {
                if (node != null && !node.id().isBlank()) {
                    nodeById.put(node.id(), node);
                }
            }
        }
        List<NodeSnapshot> nodes = new ArrayList<>();
        List<String> executionOrder = new ArrayList<>();
        List<String> successfulNodeIds = new ArrayList<>();
        List<String> failedNodeIds = new ArrayList<>();
        List<String> blockedNodeIds = new ArrayList<>();
        String failureSummary = "";
        if (result != null) {
            executionOrder.addAll(result.executionOrder());
            if (result.finalResult() != null) {
                failureSummary = safe(result.finalResult().output());
            }
            for (TaskGraphExecutionResult.NodeResult nodeResult : result.nodeResults()) {
                if (nodeResult == null) {
                    continue;
                }
                SkillResult skillResult = nodeResult.result();
                boolean success = skillResult != null && skillResult.success();
                String status = safe(nodeResult.status());
                if ("success".equalsIgnoreCase(status) || success) {
                    successfulNodeIds.add(nodeResult.nodeId());
                } else if ("blocked".equalsIgnoreCase(status)) {
                    blockedNodeIds.add(nodeResult.nodeId());
                } else {
                    failedNodeIds.add(nodeResult.nodeId());
                }
                nodes.add(new NodeSnapshot(
                        nodeResult.nodeId(),
                        nodeResult.target(),
                        status,
                        success,
                        nodeResult.usedFallback(),
                        skillResult == null ? "" : safe(skillResult.output()),
                        skillResult == null ? "" : safe(skillResult.output())
                ));
            }
        }
        for (TaskNode node : graph == null ? List.<TaskNode>of() : graph.nodes()) {
            if (node == null) {
                continue;
            }
            if (nodes.stream().noneMatch(snapshot -> snapshot.nodeId().equals(node.id()))) {
                nodes.add(new NodeSnapshot(node.id(), node.target(), "pending", false, false, "", ""));
            }
        }
        return new ExecutionSnapshot(
                traceId,
                userId,
                graph,
                result,
                contextAttributes,
                nodes,
                executionOrder,
                successfulNodeIds,
                failedNodeIds,
                blockedNodeIds,
                failureSummary,
                Instant.now()
        );
    }

    public static ExecutionSnapshot fromSkill(String traceId,
                                              String userId,
                                              String skillName,
                                              SkillResult failure,
                                              Map<String, Object> contextAttributes) {
        String safeSkill = normalize(skillName);
        NodeSnapshot node = new NodeSnapshot(
                safeSkill.isBlank() ? "retry-1" : safeSkill,
                safeSkill,
                "failed",
                false,
                false,
                failure == null ? "" : safe(failure.output()),
                failure == null ? "" : safe(failure.output())
        );
        return new ExecutionSnapshot(
                traceId,
                userId,
                new TaskGraph(List.of(), List.of()),
                null,
                contextAttributes,
                List.of(node),
                List.of(),
                List.of(),
                List.of(node.nodeId()),
                List.of(),
                failure == null ? "" : safe(failure.output()),
                Instant.now()
        );
    }

    public Optional<NodeSnapshot> node(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return Optional.empty();
        }
        return nodes.stream().filter(snapshot -> nodeId.equals(snapshot.nodeId())).findFirst();
    }

    public Map<String, NodeSnapshot> nodeIndex() {
        if (nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, NodeSnapshot> index = new LinkedHashMap<>();
        for (NodeSnapshot node : nodes) {
            if (node != null && !node.nodeId().isBlank()) {
                index.put(node.nodeId(), node);
            }
        }
        return Map.copyOf(index);
    }

    public boolean hasFailures() {
        return !failedNodeIds.isEmpty();
    }

    public boolean hasBlockedNodes() {
        return !blockedNodeIds.isEmpty();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record NodeSnapshot(String nodeId,
                               String target,
                               String status,
                               boolean success,
                               boolean usedFallback,
                               String output,
                               String failureReason) {

        public NodeSnapshot {
            nodeId = normalize(nodeId);
            target = normalize(target);
            status = normalize(status);
            output = safe(output);
            failureReason = safe(failureReason);
        }

        private static String normalize(String value) {
            if (value == null) {
                return "";
            }
            String normalized = value.trim();
            return normalized.isBlank() ? "" : normalized;
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}

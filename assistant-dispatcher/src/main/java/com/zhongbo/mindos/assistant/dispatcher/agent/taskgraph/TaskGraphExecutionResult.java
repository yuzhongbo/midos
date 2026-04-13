package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record TaskGraphExecutionResult(SkillResult finalResult,
                                       List<NodeResult> nodeResults,
                                       Map<String, Object> contextAttributes,
                                       List<String> executionOrder) {

    public TaskGraphExecutionResult {
        nodeResults = nodeResults == null ? List.of() : List.copyOf(nodeResults);
        contextAttributes = contextAttributes == null ? Map.of() : Map.copyOf(contextAttributes);
        executionOrder = executionOrder == null ? List.of() : List.copyOf(executionOrder);
    }

    public boolean success() {
        return finalResult != null && finalResult.success();
    }

    public List<String> successfulNodeIds() {
        return nodeResults.stream()
                .filter(node -> node != null && node.result() != null && node.result().success())
                .map(NodeResult::nodeId)
                .toList();
    }

    public List<String> failedNodeIds() {
        return nodeResults.stream()
                .filter(node -> node != null && (node.result() == null || !node.result().success()))
                .map(NodeResult::nodeId)
                .toList();
    }

    public List<String> failedTargets() {
        return nodeResults.stream()
                .filter(node -> node != null && (node.result() == null || !node.result().success()))
                .map(NodeResult::target)
                .filter(target -> target != null && !target.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    public record NodeResult(String nodeId,
                             String target,
                             String status,
                             SkillResult result,
                             boolean usedFallback,
                             int attempts) {

        public NodeResult(String nodeId,
                          String target,
                          String status,
                          SkillResult result,
                          boolean usedFallback) {
            this(nodeId, target, status, result, usedFallback, 1);
        }

        public NodeResult {
            attempts = Math.max(1, attempts);
        }
    }
}

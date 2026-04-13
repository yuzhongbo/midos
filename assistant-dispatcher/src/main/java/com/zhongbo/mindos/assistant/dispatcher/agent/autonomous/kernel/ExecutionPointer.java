package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.LinkedHashSet;
import java.util.List;

public record ExecutionPointer(String currentNodeId,
                               List<String> completedNodeIds,
                               List<String> failedNodeIds,
                               List<String> failedTargets,
                               int cycle) {

    public ExecutionPointer {
        currentNodeId = currentNodeId == null ? "" : currentNodeId.trim();
        completedNodeIds = completedNodeIds == null ? List.of() : List.copyOf(completedNodeIds);
        failedNodeIds = failedNodeIds == null ? List.of() : List.copyOf(failedNodeIds);
        failedTargets = failedTargets == null ? List.of() : List.copyOf(failedTargets);
        cycle = Math.max(1, cycle);
    }

    public static ExecutionPointer initial() {
        return new ExecutionPointer("", List.of(), List.of(), List.of(), 1);
    }

    public ExecutionPointer advance(String nextNodeId,
                                    List<String> nextCompletedNodeIds,
                                    List<String> nextFailedNodeIds,
                                    List<String> nextFailedTargets) {
        LinkedHashSet<String> completed = new LinkedHashSet<>(completedNodeIds);
        if (nextCompletedNodeIds != null) {
            completed.addAll(nextCompletedNodeIds);
        }
        LinkedHashSet<String> failed = new LinkedHashSet<>(failedNodeIds);
        if (nextFailedNodeIds != null) {
            failed.addAll(nextFailedNodeIds);
        }
        LinkedHashSet<String> failedTargetSet = new LinkedHashSet<>(failedTargets);
        if (nextFailedTargets != null) {
            failedTargetSet.addAll(nextFailedTargets);
        }
        return new ExecutionPointer(
                nextNodeId,
                completed.isEmpty() ? List.of() : List.copyOf(completed),
                failed.isEmpty() ? List.of() : List.copyOf(failed),
                failedTargetSet.isEmpty() ? List.of() : List.copyOf(failedTargetSet),
                cycle + 1
        );
    }
}

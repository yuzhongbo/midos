package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DefaultRecoveryManager implements RecoveryManager {

    private static final Set<String> TRANSIENT_HINTS = Set.of(
            "timeout", "timed out", "http", "502", "503", "429", "network", "connect",
            "upstream", "transient", "temporary", "暂时", "超时", "重试", "请求失败"
    );

    private static final Set<String> MISSING_HINTS = Set.of(
            "missing", "缺少", "不存在", "not found", "empty", "null", "无数据", "参数",
            "required", "未找到", "缺失", "no data", "no result"
    );

    @Override
    public RecoveryReport planRetry(String traceId,
                                    String skillName,
                                    SkillResult failure,
                                    Map<String, Object> currentContext,
                                    List<PlanStepDto> executedSteps) {
        String userId = stringValue(currentContext == null ? null : currentContext.get("userId"));
        ExecutionSnapshot snapshot = ExecutionSnapshot.fromSkill(traceId, userId, skillName, failure, currentContext);
        List<String> clearKeys = new ArrayList<>(List.of(
                "task.last.output",
                "task.last.skill",
                "task.last.success",
                "recovery.stage",
                "recovery.skillName",
                "recovery.failure"
        ));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("recovery.stage", "retry");
        patch.put("recovery.skillName", safe(skillName));
        patch.put("recovery.failure", failure == null ? "" : safe(failure.output()));
        patch.put("recovery.retryCount", 1);
        if (executedSteps != null && !executedSteps.isEmpty()) {
            patch.put("recovery.executedSteps", executedSteps.size());
        }
        RecoveryAction action = RecoveryAction.retry(
                snapshot.failedNodeIds().isEmpty() ? safe(skillName) : snapshot.failedNodeIds().get(0),
                safe(skillName),
                1,
                clearKeys,
                patch,
                classifyRetryReason(failure)
        );
        return new RecoveryReport(
                traceId,
                "retry",
                false,
                snapshot,
                List.of(action),
                List.of(action.nodeId()),
                List.of(),
                List.of(),
                clearKeys,
                patch,
                new TaskGraph(List.of(), List.of()),
                "Prepared retry for " + safe(skillName)
        );
    }

    @Override
    public RecoveryReport planRollback(String traceId,
                                       TaskGraph graph,
                                       TaskGraphExecutionResult result,
                                       Map<String, Object> currentContext) {
        ExecutionSnapshot snapshot = ExecutionSnapshot.from(traceId, userId(currentContext), graph, result, currentContext);
        if (!snapshot.hasFailures() && !snapshot.hasBlockedNodes()) {
            return RecoveryReport.noop(traceId, "rollback", "no task graph state to recover");
        }

        Map<String, TaskNode> nodesById = indexNodes(graph);
        LinkedHashSet<String> clearKeys = new LinkedHashSet<>();
        List<RecoveryAction> actions = new ArrayList<>();
        List<String> retryNodeIds = new ArrayList<>();
        List<String> fallbackNodeIds = new ArrayList<>();
        List<String> skippedNodeIds = new ArrayList<>();

        if (result != null && result.nodeResults() != null) {
            for (TaskGraphExecutionResult.NodeResult nodeResult : result.nodeResults()) {
                if (nodeResult == null) {
                    continue;
                }
                TaskNode node = nodesById.get(nodeResult.nodeId());
                clearKeys.addAll(clearTaskKeys(nodeResult.nodeId(), node));
            }
        }

        for (ExecutionSnapshot.NodeSnapshot nodeSnapshot : snapshot.nodes()) {
            if (nodeSnapshot == null) {
                continue;
            }
            if (nodeSnapshot.success()) {
                continue;
            }
            if ("blocked".equalsIgnoreCase(nodeSnapshot.status())) {
                continue;
            }
            TaskNode node = nodesById.get(nodeSnapshot.nodeId());
            FailureKind kind = classifyFailure(nodeSnapshot, node);
            switch (kind) {
                case TRANSIENT -> {
                    RecoveryAction action = RecoveryAction.retry(
                            nodeSnapshot.nodeId(),
                            safe(nodeSnapshot.target()),
                            1,
                            clearTaskKeys(nodeSnapshot.nodeId(), node),
                            nodePatch(nodeSnapshot, node, "retry", "", ""),
                            classifyTransientReason(nodeSnapshot)
                    );
                    actions.add(action);
                    retryNodeIds.add(nodeSnapshot.nodeId());
                }
                case MISSING_DATA -> {
                    String fallbackTarget = resolveFallbackTarget(node, currentContext);
                    String syntheticOutput = resolveFallbackOutput(node, currentContext);
                    if (!fallbackTarget.isBlank() || !syntheticOutput.isBlank()) {
                        RecoveryAction action = RecoveryAction.fallback(
                                nodeSnapshot.nodeId(),
                                safe(nodeSnapshot.target()),
                                fallbackTarget,
                                syntheticOutput,
                                clearTaskKeys(nodeSnapshot.nodeId(), node),
                                nodePatch(nodeSnapshot, node, "fallback", fallbackTarget, syntheticOutput),
                                classifyMissingReason(nodeSnapshot, fallbackTarget)
                        );
                        actions.add(action);
                        fallbackNodeIds.add(nodeSnapshot.nodeId());
                    } else {
                        RecoveryAction action = RecoveryAction.skip(
                                nodeSnapshot.nodeId(),
                                safe(nodeSnapshot.target()),
                                syntheticOutputForSkip(nodeSnapshot),
                                node != null && node.optional(),
                                clearTaskKeys(nodeSnapshot.nodeId(), node),
                                nodePatch(nodeSnapshot, node, "skip", "", ""),
                                "missing data without fallback"
                        );
                        actions.add(action);
                        skippedNodeIds.add(nodeSnapshot.nodeId());
                    }
                }
                case OPTIONAL -> {
                    RecoveryAction action = RecoveryAction.skip(
                            nodeSnapshot.nodeId(),
                            safe(nodeSnapshot.target()),
                            syntheticOutputForSkip(nodeSnapshot),
                            node != null && node.optional(),
                            clearTaskKeys(nodeSnapshot.nodeId(), node),
                            nodePatch(nodeSnapshot, node, "skip", "", ""),
                            "optional node skipped"
                    );
                    actions.add(action);
                    skippedNodeIds.add(nodeSnapshot.nodeId());
                }
                case UNKNOWN -> {
                    RecoveryAction action = RecoveryAction.rollback(
                            nodeSnapshot.nodeId(),
                            safe(nodeSnapshot.target()),
                            clearTaskKeys(nodeSnapshot.nodeId(), node),
                            nodePatch(nodeSnapshot, node, "rollback", "", ""),
                            classifyUnknownReason(nodeSnapshot)
                    );
                    actions.add(action);
                }
            }
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("recovery.stage", "rollback");
        patch.put("recovery.traceId", traceId);
        patch.put("recovery.failedNodeIds", snapshot.failedNodeIds());
        patch.put("recovery.blockedNodeIds", snapshot.blockedNodeIds());
        patch.put("recovery.retryNodeIds", retryNodeIds);
        patch.put("recovery.fallbackNodeIds", fallbackNodeIds);
        patch.put("recovery.skipNodeIds", skippedNodeIds);
        patch.put("recovery.actionCount", actions.size());
        patch.put("recovery.rollbackApplied", !clearKeys.isEmpty());
        for (RecoveryAction action : actions) {
            patch.putAll(action.contextPatch());
        }

        boolean rerunRecommended = actions.stream().anyMatch(RecoveryAction::isNodeAction);
        TaskGraph recoveryGraph = rerunRecommended && graph != null ? graph : new TaskGraph(List.of(), List.of());
        String summary = buildSummary(snapshot, retryNodeIds, fallbackNodeIds, skippedNodeIds, actions);
        return new RecoveryReport(
                traceId,
                "rollback",
                !clearKeys.isEmpty(),
                snapshot,
                actions,
                retryNodeIds,
                fallbackNodeIds,
                skippedNodeIds,
                new ArrayList<>(clearKeys),
                patch,
                recoveryGraph,
                summary
        );
    }

    private Map<String, TaskNode> indexNodes(TaskGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return Map.of();
        }
        Map<String, TaskNode> nodes = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) {
            if (node != null && !node.id().isBlank()) {
                nodes.put(node.id(), node);
            }
        }
        return nodes;
    }

    private List<String> clearTaskKeys(String nodeId, TaskNode node) {
        List<String> keys = new ArrayList<>();
        if (nodeId == null || nodeId.isBlank()) {
            return keys;
        }
        String normalizedNodeId = nodeId.trim();
        keys.add("task." + normalizedNodeId + ".output");
        keys.add("task." + normalizedNodeId + ".skill");
        keys.add("task." + normalizedNodeId + ".success");
        if (node != null && !node.saveAs().isBlank()) {
            String saveAs = node.saveAs().trim();
            keys.add("task." + saveAs + ".output");
            keys.add("task." + saveAs + ".skill");
            keys.add("task." + saveAs + ".success");
        }
        return keys;
    }

    private Map<String, Object> nodePatch(ExecutionSnapshot.NodeSnapshot snapshot,
                                          TaskNode node,
                                          String stage,
                                          String fallbackTarget,
                                          String syntheticOutput) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (snapshot != null && !snapshot.nodeId().isBlank()) {
            patch.put("recovery.node." + snapshot.nodeId() + ".stage", stage);
            patch.put("recovery.node." + snapshot.nodeId() + ".reason", safe(snapshot.failureReason()));
            patch.put("recovery.node." + snapshot.nodeId() + ".target", safe(snapshot.target()));
        }
        if (node != null && !node.saveAs().isBlank()) {
            patch.put("recovery.node." + node.saveAs() + ".saveAs", node.saveAs());
        }
        if (!fallbackTarget.isBlank()) {
            patch.put("recovery.node." + snapshot.nodeId() + ".fallbackTarget", fallbackTarget);
        }
        if (!syntheticOutput.isBlank()) {
            patch.put("recovery.node." + snapshot.nodeId() + ".syntheticOutput", syntheticOutput);
        }
        return patch;
    }

    private FailureKind classifyFailure(ExecutionSnapshot.NodeSnapshot nodeSnapshot, TaskNode node) {
        String text = normalize((nodeSnapshot == null ? "" : nodeSnapshot.failureReason()) + " " + (nodeSnapshot == null ? "" : nodeSnapshot.output()));
        if (containsAny(text, TRANSIENT_HINTS)) {
            return FailureKind.TRANSIENT;
        }
        if (containsAny(text, MISSING_HINTS)) {
            return FailureKind.MISSING_DATA;
        }
        if (node != null && node.optional()) {
            return FailureKind.OPTIONAL;
        }
        return FailureKind.UNKNOWN;
    }

    private String resolveFallbackTarget(TaskNode node, Map<String, Object> currentContext) {
        List<String> candidates = new ArrayList<>();
        if (node != null && node.params() != null) {
            candidates.add(stringValue(node.params().get("fallbackTarget")));
            candidates.add(stringValue(node.params().get("fallbackSkill")));
            candidates.add(stringValue(node.params().get("alternateTarget")));
        }
        if (currentContext != null) {
            Object globalFallback = currentContext.get("recovery.fallbackTarget");
            candidates.add(stringValue(globalFallback));
            if (node != null && !node.id().isBlank()) {
                candidates.add(stringValue(currentContext.get("recovery.fallbackTarget." + node.id())));
            }
        }
        return firstNonBlank(candidates);
    }

    private String resolveFallbackOutput(TaskNode node, Map<String, Object> currentContext) {
        List<String> candidates = new ArrayList<>();
        if (node != null && node.params() != null) {
            candidates.add(stringValue(node.params().get("fallbackOutput")));
            candidates.add(stringValue(node.params().get("fallbackValue")));
            candidates.add(stringValue(node.params().get("defaultOutput")));
            candidates.add(stringValue(node.params().get("defaultValue")));
        }
        if (currentContext != null) {
            Object globalFallback = currentContext.get("recovery.fallbackOutput");
            candidates.add(stringValue(globalFallback));
            if (node != null && !node.id().isBlank()) {
                candidates.add(stringValue(currentContext.get("recovery.fallbackOutput." + node.id())));
            }
        }
        return firstNonBlank(candidates);
    }

    private String syntheticOutputForSkip(ExecutionSnapshot.NodeSnapshot nodeSnapshot) {
        if (nodeSnapshot == null) {
            return "skipped";
        }
        if (!nodeSnapshot.output().isBlank()) {
            return nodeSnapshot.output();
        }
        return "skipped:" + nodeSnapshot.target();
    }

    private String buildSummary(ExecutionSnapshot snapshot,
                                List<String> retryNodeIds,
                                List<String> fallbackNodeIds,
                                List<String> skippedNodeIds,
                                List<RecoveryAction> actions) {
        StringBuilder builder = new StringBuilder();
        builder.append("Recovered ");
        builder.append(snapshot == null ? 0 : snapshot.failedNodeIds().size());
        builder.append(" failed node(s)");
        if (!retryNodeIds.isEmpty()) {
            builder.append(", retry=").append(retryNodeIds);
        }
        if (!fallbackNodeIds.isEmpty()) {
            builder.append(", fallback=").append(fallbackNodeIds);
        }
        if (!skippedNodeIds.isEmpty()) {
            builder.append(", skip=").append(skippedNodeIds);
        }
        builder.append(", actions=").append(actions == null ? 0 : actions.size());
        return builder.toString();
    }

    private String classifyRetryReason(SkillResult failure) {
        return containsAny(normalize(failure == null ? "" : failure.output()), TRANSIENT_HINTS)
                ? "transient api failure"
                : "retry requested";
    }

    private String classifyTransientReason(ExecutionSnapshot.NodeSnapshot nodeSnapshot) {
        return containsAny(normalize(nodeSnapshot == null ? "" : nodeSnapshot.failureReason()), TRANSIENT_HINTS)
                ? "transient failure"
                : "retry";
    }

    private String classifyMissingReason(ExecutionSnapshot.NodeSnapshot nodeSnapshot, String fallbackTarget) {
        if (!fallbackTarget.isBlank()) {
            return "missing data -> fallback " + fallbackTarget;
        }
        return "missing data -> skip";
    }

    private String classifyUnknownReason(ExecutionSnapshot.NodeSnapshot nodeSnapshot) {
        return nodeSnapshot == null ? "unknown failure" : safe(nodeSnapshot.failureReason());
    }

    private boolean containsAny(String input, Set<String> keywords) {
        if (input == null || input.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String userId(Map<String, Object> currentContext) {
        if (currentContext == null) {
            return "";
        }
        return stringValue(currentContext.get("userId"));
    }

    private enum FailureKind {
        TRANSIENT,
        MISSING_DATA,
        OPTIONAL,
        UNKNOWN
    }
}

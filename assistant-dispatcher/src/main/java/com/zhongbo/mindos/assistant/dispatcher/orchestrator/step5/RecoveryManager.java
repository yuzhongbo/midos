package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;

import java.util.List;
import java.util.Map;

public interface RecoveryManager {

    RecoveryReport planRetry(String traceId,
                             String skillName,
                             SkillResult failure,
                             Map<String, Object> currentContext,
                             List<PlanStepDto> executedSteps);

    RecoveryReport planRollback(String traceId,
                                TaskGraph graph,
                                TaskGraphExecutionResult result,
                                Map<String, Object> currentContext);

    record RecoveryReport(String traceId,
                          String stage,
                          boolean rollbackApplied,
                          ExecutionSnapshot snapshot,
                          List<RecoveryAction> actions,
                          List<String> retryNodeIds,
                          List<String> fallbackNodeIds,
                          List<String> skippedNodeIds,
                          List<String> clearKeys,
                          Map<String, Object> contextPatch,
                          TaskGraph recoveryGraph,
                          String summary) {

        public RecoveryReport {
            traceId = traceId == null ? "" : traceId.trim();
            stage = stage == null ? "" : stage.trim();
            snapshot = snapshot;
            actions = actions == null ? List.of() : List.copyOf(actions);
            retryNodeIds = retryNodeIds == null ? List.of() : List.copyOf(retryNodeIds);
            fallbackNodeIds = fallbackNodeIds == null ? List.of() : List.copyOf(fallbackNodeIds);
            skippedNodeIds = skippedNodeIds == null ? List.of() : List.copyOf(skippedNodeIds);
            clearKeys = clearKeys == null ? List.of() : List.copyOf(clearKeys);
            contextPatch = contextPatch == null ? Map.of() : Map.copyOf(contextPatch);
            recoveryGraph = recoveryGraph == null ? new TaskGraph(List.of(), List.of()) : recoveryGraph;
            summary = summary == null ? "" : summary.trim();
        }

        public static RecoveryReport noop(String traceId, String stage, String summary) {
            return new RecoveryReport(traceId, stage, false, null, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), new TaskGraph(List.of(), List.of()), summary);
        }

        public boolean shouldReexecute() {
            if (actions == null || actions.isEmpty()) {
                return false;
            }
            return actions.stream().anyMatch(RecoveryAction::isNodeAction);
        }

        public Map<String, RecoveryAction> actionMap() {
            if (actions == null || actions.isEmpty()) {
                return Map.of();
            }
            Map<String, RecoveryAction> actionMap = new java.util.LinkedHashMap<>();
            for (RecoveryAction action : actions) {
                if (action != null && action.isNodeAction() && !action.nodeId().isBlank()) {
                    actionMap.put(action.nodeId(), action);
                }
            }
            return actionMap.isEmpty() ? Map.of() : Map.copyOf(actionMap);
        }

        public boolean hasRecoveryGraph() {
            return recoveryGraph != null && !recoveryGraph.isEmpty();
        }
    }
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultRecoveryManager implements RecoveryManager {

    @Override
    public RecoveryReport planRetry(String traceId,
                                    String skillName,
                                    SkillResult failure,
                                    Map<String, Object> currentContext,
                                    List<PlanStepDto> executedSteps) {
        if (failure == null) {
            return RecoveryReport.noop(traceId, "retry", "no failure to recover");
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("recovery.stage", "retry");
        patch.put("recovery.skillName", skillName == null ? "" : skillName);
        patch.put("recovery.failure", failure.output());
        List<String> actions = new ArrayList<>();
        actions.add("clear-transient-context");
        if (executedSteps != null && !executedSteps.isEmpty()) {
            actions.add("executedSteps=" + executedSteps.size());
        }
        return new RecoveryReport(traceId, "retry", false, List.of("task.last.output", "task.last.skill", "task.last.success"), patch, actions, "Prepared retry context for " + skillName);
    }

    @Override
    public RecoveryReport planRollback(String traceId,
                                       TaskGraph graph,
                                       TaskGraphExecutionResult result,
                                       Map<String, Object> currentContext) {
        if (graph == null || graph.isEmpty() || result == null || result.nodeResults() == null || result.nodeResults().isEmpty()) {
            return RecoveryReport.noop(traceId, "rollback", "no task graph state to roll back");
        }
        Map<String, String> nodeToSaveAs = new LinkedHashMap<>();
        for (TaskNode node : graph.nodes()) {
            nodeToSaveAs.put(node.id(), node.saveAs());
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        List<String> clearKeys = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (TaskGraphExecutionResult.NodeResult nodeResult : result.nodeResults()) {
            if (nodeResult == null || nodeResult.result() == null) {
                continue;
            }
            clearTaskKeys(clearKeys, nodeResult.nodeId(), nodeToSaveAs.getOrDefault(nodeResult.nodeId(), ""));
            actions.add("clear-" + nodeResult.nodeId());
        }
        patch.put("recovery.stage", "rollback");
        patch.put("recovery.graphNodes", graph.nodes().size());
        return new RecoveryReport(
                traceId,
                "rollback",
                true,
                clearKeys,
                patch,
                actions,
                "Rolled back " + actions.size() + " executed task node(s)"
        );
    }

    private void clearTaskKeys(List<String> clearKeys, String nodeId, String saveAs) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        clearKeys.add("task." + nodeId + ".output");
        clearKeys.add("task." + nodeId + ".skill");
        clearKeys.add("task." + nodeId + ".success");
        if (saveAs != null && !saveAs.isBlank()) {
            clearKeys.add("task." + saveAs + ".output");
            clearKeys.add("task." + saveAs + ".skill");
            clearKeys.add("task." + saveAs + ".success");
        }
    }
}

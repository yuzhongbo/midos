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
                          List<String> clearKeys,
                          Map<String, Object> contextPatch,
                          List<String> actions,
                          String summary) {

        public RecoveryReport {
            traceId = traceId == null ? "" : traceId.trim();
            stage = stage == null ? "" : stage.trim();
            clearKeys = clearKeys == null ? List.of() : List.copyOf(clearKeys);
            contextPatch = contextPatch == null ? Map.of() : Map.copyOf(contextPatch);
            actions = actions == null ? List.of() : List.copyOf(actions);
            summary = summary == null ? "" : summary.trim();
        }

        public static RecoveryReport noop(String traceId, String stage, String summary) {
            return new RecoveryReport(traceId, stage, false, List.of(), Map.of(), List.of("noop"), summary);
        }
    }
}

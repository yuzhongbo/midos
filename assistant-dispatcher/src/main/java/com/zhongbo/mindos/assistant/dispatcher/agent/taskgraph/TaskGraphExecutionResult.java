package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.List;
import java.util.Map;

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

    public record NodeResult(String nodeId,
                             String target,
                             String status,
                             SkillResult result,
                             boolean usedFallback) {
    }
}

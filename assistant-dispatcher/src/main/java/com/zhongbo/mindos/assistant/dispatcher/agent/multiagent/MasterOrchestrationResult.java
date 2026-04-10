package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;

import java.util.List;
import java.util.Map;

public record MasterOrchestrationResult(SkillResult result,
                                       ExecutionTraceDto trace,
                                       List<AgentMessage> transcript,
                                       Map<String, Object> sharedState) {

    public MasterOrchestrationResult {
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        sharedState = sharedState == null ? Map.of() : Map.copyOf(sharedState);
    }

    public boolean success() {
        return result != null && result.success();
    }
}

package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;

import java.util.Optional;

record LlmDetectionResult(Optional<SkillResult> result,
                          Optional<SkillDsl> skillDsl,
                          Optional<SkillResult> directResult,
                          Optional<ExecutionTraceDto> trace,
                          boolean usedFallback) {

    static LlmDetectionResult empty() {
        return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }
}

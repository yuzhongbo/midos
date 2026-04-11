package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;

import java.util.Map;

public interface ReflectionAgent {

    ReflectionResult reflect(ReflectionRequest request);

    default ReflectionResult reflect(String userId,
                                     String userInput,
                                     ExecutionTraceDto trace,
                                     SkillResult result,
                                     Map<String, Object> params,
                                     Map<String, Object> context) {
        return reflect(ReflectionRequest.of(userId, userInput, trace, result, params, context));
    }

    default ReflectionResult reflect(String userId,
                                     ExecutionTraceDto trace,
                                     SkillResult result,
                                     Map<String, Object> params,
                                     Map<String, Object> context) {
        return reflect(userId, "", trace, result, params, context);
    }
}

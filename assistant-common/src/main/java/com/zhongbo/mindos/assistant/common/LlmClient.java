package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dsl.SkillDSL;

import java.util.HashMap;
import java.util.Map;

public interface LlmClient {
    String generateResponse(String prompt, Map<String, Object> context);

    default SkillDSL parseSkillCall(String userInput) {
        return null;
    }

    default String generateReply(String userId, String input) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userId);
        return generateResponse(input, context);
    }
}


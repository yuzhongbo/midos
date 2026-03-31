package com.zhongbo.mindos.assistant.common;

import com.zhongbo.mindos.assistant.common.dsl.SkillDSL;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmClient {
    String generateResponse(String prompt, Map<String, Object> context);

    default void streamResponse(String prompt, Map<String, Object> context, Consumer<String> onDelta) {
        String reply = generateResponse(prompt, context);
        if (onDelta != null && reply != null && !reply.isBlank()) {
            onDelta.accept(reply);
        }
    }

    default SkillDSL parseSkillCall(String userInput) {
        return null;
    }

    default String generateReply(String userId, String input) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userId);
        return generateResponse(input, context);
    }
}


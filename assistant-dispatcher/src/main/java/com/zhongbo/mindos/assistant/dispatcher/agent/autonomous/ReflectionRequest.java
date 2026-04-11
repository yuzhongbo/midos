package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ReflectionRequest(String userId,
                                String userInput,
                                ExecutionTraceDto trace,
                                SkillResult result,
                                Map<String, Object> params,
                                Map<String, Object> context) {

    public ReflectionRequest {
        userId = normalize(userId);
        userInput = normalize(userInput);
        params = normalizeMap(params);
        context = normalizeMap(context);
    }

    public static ReflectionRequest of(String userId,
                                       String userInput,
                                       ExecutionTraceDto trace,
                                       SkillResult result,
                                       Map<String, Object> params,
                                       Map<String, Object> context) {
        return new ReflectionRequest(userId, userInput, trace, result, params, context);
    }

    public boolean success() {
        return result != null && result.success();
    }

    private static Map<String, Object> normalizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            if (key.isBlank()) {
                continue;
            }
            normalized.put(key, entry.getValue());
        }
        return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

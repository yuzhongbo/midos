package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.List;
import java.util.stream.Collectors;

final class FailureNormalizer {

    SkillResult unifiedFailureResult(String userInput,
                                     String intent,
                                     List<String> attemptedCandidates,
                                     SkillResult failure) {
        String safeIntent = escapeJson(intent);
        String safeInput = escapeJson(userInput);
        String safeMessage = escapeJson(failure == null ? "all candidates failed" : failure.output());
        String safeLastSkill = escapeJson(failure == null ? "decision.orchestrator" : failure.skillName());
        String candidatesJson = attemptedCandidates == null || attemptedCandidates.isEmpty()
                ? "[]"
                : attemptedCandidates.stream()
                .map(candidate -> "\"" + escapeJson(candidate) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        String payload = "{"
                + "\"status\":\"failed\","
                + "\"intent\":\"" + safeIntent + "\","
                + "\"userInput\":\"" + safeInput + "\","
                + "\"attemptedCandidates\":" + candidatesJson + ","
                + "\"lastSkill\":\"" + safeLastSkill + "\","
                + "\"message\":\"" + safeMessage + "\""
                + "}";
        return SkillResult.failure("decision.orchestrator", payload);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

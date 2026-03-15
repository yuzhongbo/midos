package com.zhongbo.mindos.assistant.common.dsl;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured response for extended Skill DSL execution.
 * Includes final outputs plus execution logs for each skill step.
 */
public record SkillResponse(
        boolean success,
        List<SkillOutput> outputs,
        List<SkillExecutionLog> executionLogs,
        Map<String, Object> metadata
) {

    public SkillResponse {
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        executionLogs = executionLogs == null ? List.of() : List.copyOf(executionLogs);
        metadata = immutableMap(metadata);
    }

    public static SkillResponse success(List<SkillOutput> outputs, List<SkillExecutionLog> executionLogs) {
        return new SkillResponse(true, outputs, executionLogs, Map.of());
    }

    public static SkillResponse failure(List<SkillExecutionLog> executionLogs, Map<String, Object> metadata) {
        return new SkillResponse(false, List.of(), executionLogs, metadata);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public record SkillOutput(
            String skill,
            Object output,
            Map<String, Object> metadata
    ) {
        public SkillOutput {
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    public record SkillExecutionLog(
            String skill,
            String status,
            String message,
            Instant executedAt,
            Map<String, Object> details
    ) {
        public SkillExecutionLog {
            executedAt = executedAt == null ? Instant.now() : executedAt;
            details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }
}


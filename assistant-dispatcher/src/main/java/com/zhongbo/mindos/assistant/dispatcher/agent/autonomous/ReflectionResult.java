package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReflectionResult(boolean success,
                               String rootCause,
                               String improvement,
                               String pattern,
                               Map<String, Double> dimensionScores,
                               List<String> signals,
                               boolean proceduralWritten,
                               boolean semanticWritten,
                               Instant reflectedAt) {

    public ReflectionResult {
        rootCause = rootCause == null ? "" : rootCause.trim();
        improvement = improvement == null ? "" : improvement.trim();
        pattern = pattern == null ? "unknown" : pattern.trim();
        dimensionScores = dimensionScores == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(dimensionScores));
        signals = signals == null ? List.of() : List.copyOf(signals);
        reflectedAt = reflectedAt == null ? Instant.now() : reflectedAt;
    }

    public ReflectionResult withMemoryWrites(boolean proceduralWritten, boolean semanticWritten) {
        return new ReflectionResult(
                success,
                rootCause,
                improvement,
                pattern,
                dimensionScores,
                signals,
                proceduralWritten,
                semanticWritten,
                reflectedAt
        );
    }

    public String summary() {
        return "pattern=" + pattern
                + ",success=" + success
                + ",rootCause=" + rootCause
                + ",improvement=" + improvement;
    }
}

package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface TraceLogger {

    String start(String userId, String intent, String userInput);

    void event(String traceId, String phase, String action, Map<String, Object> details);

    void finish(String traceId, boolean success, String summary, Map<String, Object> details);

    List<TraceEvent> recent(String traceId);

    record TraceEvent(Instant timestamp,
                      String traceId,
                      String userId,
                      String phase,
                      String action,
                      String summary,
                      Map<String, Object> details) {

        public TraceEvent {
            timestamp = timestamp == null ? Instant.now() : timestamp;
            traceId = traceId == null ? "" : traceId.trim();
            userId = userId == null ? "" : userId.trim();
            phase = phase == null ? "" : phase.trim();
            action = action == null ? "" : action.trim();
            summary = summary == null ? "" : summary.trim();
            details = sanitize(details);
        }

        private static Map<String, Object> sanitize(Map<String, Object> details) {
            if (details == null || details.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                sanitized.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
            return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
        }
    }
}

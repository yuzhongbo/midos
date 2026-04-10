package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

@Component
public class InMemoryTraceLogger implements TraceLogger {

    private static final Logger LOGGER = Logger.getLogger(InMemoryTraceLogger.class.getName());

    private final int maxRecentEvents;
    private final Map<String, Deque<TraceEvent>> eventsByTrace = new ConcurrentHashMap<>();
    private final Deque<TraceEvent> recentEvents = new ConcurrentLinkedDeque<>();

    public InMemoryTraceLogger(@Value("${mindos.dispatcher.step5.trace.max-recent-events:800}") int maxRecentEvents) {
        this.maxRecentEvents = Math.max(100, maxRecentEvents);
    }

    @Override
    public String start(String userId, String intent, String userInput) {
        String traceId = UUID.randomUUID().toString();
        event(traceId, "trace", "start", Map.of(
                "userId", userId == null ? "" : userId,
                "intent", intent == null ? "" : intent,
                "input", cap(userInput, 180)
        ));
        return traceId;
    }

    @Override
    public void event(String traceId, String phase, String action, Map<String, Object> details) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        TraceEvent traceEvent = new TraceEvent(Instant.now(), traceId, asString(details, "userId"), phase, action, "", details);
        append(traceEvent);
        LOGGER.info(render(traceEvent));
    }

    @Override
    public void finish(String traceId, boolean success, String summary, Map<String, Object> details) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (details != null) {
            payload.putAll(details);
        }
        payload.put("success", success);
        payload.put("summary", summary == null ? "" : summary);
        TraceEvent traceEvent = new TraceEvent(Instant.now(), traceId, asString(payload, "userId"), "trace", "finish", summary, payload);
        append(traceEvent);
        LOGGER.info(render(traceEvent));
    }

    @Override
    public List<TraceEvent> recent(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        Deque<TraceEvent> deque = eventsByTrace.get(traceId.trim());
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(deque));
    }

    private void append(TraceEvent event) {
        eventsByTrace.computeIfAbsent(event.traceId(), ignored -> new ConcurrentLinkedDeque<>()).addLast(event);
        recentEvents.addLast(event);
        while (recentEvents.size() > maxRecentEvents) {
            recentEvents.pollFirst();
        }
        Deque<TraceEvent> traceEvents = eventsByTrace.get(event.traceId());
        while (traceEvents != null && traceEvents.size() > maxRecentEvents) {
            traceEvents.pollFirst();
        }
    }

    private String render(TraceEvent event) {
        return "[trace] traceId=" + event.traceId()
                + ", phase=" + event.phase()
                + ", action=" + event.action()
                + ", summary=" + cap(event.summary(), 120)
                + ", details=" + cap(String.valueOf(event.details()), 320);
    }

    private String asString(Map<String, Object> details, String key) {
        if (details == null || !details.containsKey(key)) {
            return "";
        }
        Object value = details.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 12)) + "...(truncated)";
    }
}

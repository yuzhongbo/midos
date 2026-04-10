package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProcedureTemplate(String id,
                                String userId,
                                String intent,
                                String trigger,
                                List<ProcedureStepTemplate> steps,
                                double successRate,
                                int reuseCount,
                                Instant updatedAt,
                                Map<String, Object> metadata) {

    public ProcedureTemplate {
        id = id == null ? "" : id.trim();
        userId = userId == null ? "" : userId.trim();
        intent = intent == null ? "" : intent.trim();
        trigger = trigger == null ? "" : trigger.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
        successRate = Math.max(0.0, Math.min(1.0, successRate));
        reuseCount = Math.max(0, reuseCount);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

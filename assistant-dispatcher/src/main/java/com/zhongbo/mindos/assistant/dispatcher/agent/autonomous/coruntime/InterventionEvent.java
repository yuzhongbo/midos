package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record InterventionEvent(TaskHandle handle,
                                InterventionType type,
                                Map<String, Object> payload,
                                Instant occurredAt) {

    public InterventionEvent {
        handle = handle == null ? new TaskHandle("") : handle;
        type = type == null ? InterventionType.MODIFY : type;
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}

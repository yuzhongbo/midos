package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIRuntimeKernel;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InterventionManager {

    private final AGIRuntimeKernel runtimeKernel;
    private final Map<String, CopyOnWriteArrayList<InterventionEvent>> history = new ConcurrentHashMap<>();

    public InterventionManager(AGIRuntimeKernel runtimeKernel) {
        this.runtimeKernel = runtimeKernel;
    }

    public void interrupt(TaskHandle handle) {
        if (runtimeKernel != null) {
            runtimeKernel.suspend(handle);
        }
        record(handle, InterventionType.INTERRUPT, Map.of("summary", "human-interrupt"));
    }

    public void modify(TaskHandle handle, Params newParams) {
        if (newParams == null || newParams.empty()) {
            return;
        }
        if (runtimeKernel != null) {
            runtimeKernel.updateContext(handle, newParams.values(), "human-modified");
        }
        record(handle, InterventionType.MODIFY, newParams.values());
    }

    public void override(SharedDecision decision) {
        if (decision == null || decision.handle() == null || decision.handle().isEmpty()) {
            return;
        }
        if (runtimeKernel != null) {
            if (decision.hasOverrides()) {
                runtimeKernel.updateContext(decision.handle(), decision.overrides(), "human-override");
            } else {
                runtimeKernel.suspend(decision.handle());
            }
        }
        record(decision.handle(), InterventionType.OVERRIDE, decision.attributes());
    }

    public void rollback(TaskHandle handle) {
        if (runtimeKernel != null) {
            runtimeKernel.rollback(handle);
        }
        record(handle, InterventionType.ROLLBACK, Map.of("summary", "human-rollback"));
    }

    public List<InterventionEvent> history(TaskHandle handle) {
        if (handle == null || handle.isEmpty()) {
            return List.of();
        }
        return List.copyOf(history.getOrDefault(handle.taskId(), new CopyOnWriteArrayList<>()));
    }

    private void record(TaskHandle handle, InterventionType type, Map<String, Object> payload) {
        if (handle == null || handle.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        values.put("type", type.name());
        values.put("occurredAt", Instant.now().toString());
        history.computeIfAbsent(handle.taskId(), ignored -> new CopyOnWriteArrayList<>())
                .add(new InterventionEvent(handle, type, values, Instant.now()));
    }
}

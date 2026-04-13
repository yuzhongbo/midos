package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RuntimeOptimizer {

    private final Map<String, Map<String, Object>> hintsByTaskId = new ConcurrentHashMap<>();

    public void optimize(ExecutionHistory history) {
        if (history == null || history.handle() == null || history.handle().isEmpty() || history.snapshots().isEmpty()) {
            return;
        }
        int failures = 0;
        int waits = 0;
        int suspends = 0;
        for (RuntimeState snapshot : history.snapshots()) {
            if (snapshot == null) {
                continue;
            }
            if (snapshot.state() == TaskState.FAILED) {
                failures++;
            } else if (snapshot.state() == TaskState.WAITING) {
                waits++;
            } else if (snapshot.state() == TaskState.SUSPENDED) {
                suspends++;
            }
        }
        LinkedHashMap<String, Object> hints = new LinkedHashMap<>();
        double computeBoost = 1.0 + failures * 0.10 + waits * 0.05;
        if (computeBoost > 1.0) {
            hints.put("optimizer.computeBoost", Math.min(1.5, computeBoost));
        }
        if (suspends > 0) {
            hints.put("optimizer.preferredPolicy", ExecutionPolicy.LONG_RUNNING.name());
        } else if (failures > 0 && waits > failures) {
            hints.put("optimizer.preferredPolicy", ExecutionPolicy.BATCH.name());
        }
        if (waits > 0) {
            hints.put("optimizer.memoryBoost", Math.min(1.4, 1.0 + waits * 0.08));
        }
        hintsByTaskId.put(history.handle().taskId(), Map.copyOf(hints));
    }

    public Map<String, Object> hints(TaskHandle handle) {
        if (handle == null || handle.isEmpty()) {
            return Map.of();
        }
        return hintsByTaskId.getOrDefault(handle.taskId(), Map.of());
    }
}
